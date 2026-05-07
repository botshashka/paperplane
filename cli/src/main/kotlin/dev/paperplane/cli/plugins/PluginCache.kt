package dev.paperplane.cli.plugins

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

/**
 * Manages downloaded plugin JARs under `.paperplane/cache/plugins/`. Cache key is
 * `{source}-{slug}-{version}.jar` so pinned bumps never collide and downgrades don't require a
 * fresh cache wipe. For `local:` entries the cache returns the original path unchanged — no copy,
 * since the source is already on disk.
 *
 * Downloads follow the same atomic-rename pattern as [PaperServerManager.copyPlugin]: stream to
 * `.{name}.tmp`, verify SHA512, then `ATOMIC_MOVE` into place. Partial downloads can't be mistaken
 * for complete ones because the final file is only created by the rename.
 *
 * SHA512 (not SHA256) because Modrinth's `hashes` block consistently provides sha1 and sha512 but
 * NOT sha256. We pick sha512 for strength + universal availability across Modrinth files.
 */
open class PluginCache(private val cacheDir: File) {
  private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

  /**
   * Returns the path a locked plugin *would* occupy in the cache. Does not create it and does not
   * check existence — used by [PluginResolver] to decide whether a download is needed and by [get]
   * to look up an already-cached file.
   */
  open fun pathFor(locked: LockedPlugin): File {
    if (locked.source == PluginDependency.Source.LOCAL.key) {
      // Local entries live outside the cache — the user's path IS the cache.
      return File(locked.url).takeIf { it.isAbsolute } ?: File(locked.url)
    }
    val safeVersion = locked.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return File(cacheDir, "${locked.source}-${locked.slug}-$safeVersion.jar")
  }

  /**
   * Returns the cached JAR file if present *and* its SHA matches [locked]. Returns null on cache
   * miss, partial-download leftover, or checksum mismatch — callers should treat null as "please
   * (re-)download".
   */
  open fun get(locked: LockedPlugin): File? {
    val file = pathFor(locked)
    if (!file.exists()) return null
    if (!verifySha(file, locked.sha512)) return null
    return file
  }

  /**
   * Fetches [locked] from its recorded URL (or from the local path), verifies SHA512, and places
   * the file in the cache. Returns the final cache path. If [force] is true, always re-downloads
   * even when a cached copy is present and valid.
   *
   * For `local:` entries this just hashes the file in place (and validates [locked.sha512]).
   * There's no network call and no copy.
   */
  open fun download(locked: LockedPlugin, force: Boolean = false): File =
      if (locked.source == PluginDependency.Source.LOCAL.key) verifyLocal(locked)
      else downloadRemote(locked, force)

  private fun verifyLocal(locked: LockedPlugin): File {
    val file = pathFor(locked)
    if (!file.exists()) {
      throw IOException(
          "Local plugin file missing: ${locked.url}. " +
              "Update or remove the entry in paperplane.yml."
      )
    }
    if (!verifySha(file, locked.sha512)) {
      throw IOException(
          "Local plugin '${locked.slug}' failed SHA512 verification — " +
              "the file on disk differs from what's in paperplane-lock.yml. " +
              "Re-run `ppl plugin update ${locked.slug}` if the change was intentional."
      )
    }
    return file
  }

  private fun downloadRemote(locked: LockedPlugin, force: Boolean): File {
    val target = pathFor(locked)
    if (!force && target.exists() && verifySha(target, locked.sha512)) {
      return target
    }

    cacheDir.mkdirs()
    val tmp = File(cacheDir, ".${target.name}.tmp")
    tmp.delete() // clear any leftover partial download from a prior crash

    val request = HttpRequest.newBuilder().uri(URI.create(locked.url)).build()
    val response =
        try {
          client.send(request, HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
        } catch (e: IOException) {
          tmp.delete()
          // The bare IOException from HttpClient is often unhelpful (sometimes its message is
          // null, e.g. for proxy CONNECT failures), so wrap it with slug/version/url context
          // before it reaches handleResolveErrors. Otherwise users see only "I/O error".
          throw IOException(
              "Could not download ${locked.slug} ${locked.version} from ${locked.url}: " +
                  "${e.message ?: e.javaClass.simpleName}. " +
                  "Check your network connection or run `ppl plugin update ${locked.slug}` " +
                  "if the URL has changed.",
              e,
          )
        }
    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
      tmp.delete()
      throw IOException(
          "Failed to download ${locked.slug} ${locked.version}: " +
              "HTTP ${response.statusCode()} for ${locked.url}"
      )
    }

    if (!verifySha(tmp, locked.sha512)) {
      tmp.delete()
      throw IOException(
          "SHA512 mismatch after downloading ${locked.slug} ${locked.version} — " +
              "refusing to install."
      )
    }

    atomicMoveOrFallback(tmp.toPath(), target.toPath())
    return target
  }

  /** Computes SHA512 of [file] as a 128-char lowercase hex string. */
  open fun sha512(file: File): String {
    val digest = MessageDigest.getInstance("SHA-512")
    file.inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buf)
        if (read <= 0) break
        digest.update(buf, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun verifySha(file: File, expected: String): Boolean =
      sha512(file).equals(expected, ignoreCase = true)
}

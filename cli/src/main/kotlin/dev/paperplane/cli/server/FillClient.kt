package dev.paperplane.cli.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.paperplane.cli.Versions
import dev.paperplane.cli.plugins.atomicMoveOrFallback
import dev.paperplane.cli.util.fileDigestHex
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Download coordinates for a single Paper/Velocity build's server jar, parsed from a Fill v3 build.
 * [sha256] and [size] are the build's advertised integrity metadata; either may be absent on older
 * or non-standard responses, in which case that check is skipped.
 */
internal data class ServerJar(
    val name: String,
    val url: String,
    val sha256: String? = null,
    val size: Long? = null,
)

/**
 * Thin client for the PaperMC Fill v3 API, shared by [PaperDownloader], [VelocityDownloader], and
 * [PaperVersionResolver]. Centralizes the required User-Agent header, the response-shape parsing,
 * and — crucially — integrity-checked jar downloads, so the three callers keep only their
 * project-specific selection logic.
 *
 * [projectLabel] ("Paper" / "Velocity") is woven into error messages; [timeout] applies to metadata
 * fetches when set.
 */
internal class FillClient(
    private val client: HttpClient,
    private val projectLabel: String,
    private val timeout: Duration? = null,
) {
  private val gson = Gson()

  /** GETs [url] as text with the Fill User-Agent (and optional [timeout]); throws on non-200. */
  fun fetch(url: String): String {
    val builder =
        HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", Versions.userAgent())
    if (timeout != null) builder.timeout(timeout)
    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
      throw IOException("$projectLabel API request failed: HTTP ${response.statusCode()} for $url")
    }
    return response.body()
  }

  /**
   * Parses a Fill v3 `builds/latest` response into the server jar's coordinates + integrity data.
   */
  fun parseLatestServerJar(json: String): ServerJar {
    val build = gson.fromJson(json, JsonObject::class.java)
    val serverDefault = build.getAsJsonObject("downloads").getAsJsonObject("server:default")
    return ServerJar(
        name = serverDefault.get("name").asString,
        url = serverDefault.get("url").asString,
        sha256 = serverDefault.getAsJsonObject("checksums")?.get("sha256")?.asString,
        size = serverDefault.get("size")?.asLong,
    )
  }

  /**
   * Flattens a Fill v3 project response's grouped `versions` map — {"1.21":["1.21.11",...],...} —
   * into a flat list of version strings, preserving the API's grouped, newest-first order.
   */
  fun parseVersionsMap(json: String): List<String> =
      gson.fromJson(json, JsonObject::class.java).getAsJsonObject("versions").entrySet().flatMap {
          (_, list) ->
        list.asJsonArray.map { it.asString }
      }

  /**
   * Downloads [jar] into [cacheDir] as [finalName], verifying advertised size + SHA-256 before
   * publishing. The body streams to a temp file that is atomically moved into place ONLY after
   * verification, and is always deleted on the way out, so an interrupted transfer, a truncated
   * proxy response, or a checksum mismatch can never leave a corrupt jar at the final cache path.
   */
  fun downloadVerified(jar: ServerJar, cacheDir: File, finalName: String): File {
    cacheDir.mkdirs()
    val target = File(cacheDir, finalName)
    val tmp = File(cacheDir, ".$finalName.tmp")
    try {
      val request =
          HttpRequest.newBuilder()
              .uri(URI.create(jar.url))
              .header("User-Agent", Versions.userAgent())
              .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
      if (response.statusCode() != HttpURLConnection.HTTP_OK) {
        throw IOException("Failed to download $projectLabel: HTTP ${response.statusCode()}")
      }
      verifyIntegrity(tmp, jar)
      atomicMoveOrFallback(tmp.toPath(), target.toPath())
      return target
    } finally {
      tmp.delete()
    }
  }

  private fun verifyIntegrity(file: File, jar: ServerJar) {
    jar.size?.let { expected ->
      val actual = file.length()
      if (actual != expected) {
        throw IOException(
            "$projectLabel download size mismatch: expected $expected bytes, got $actual"
        )
      }
    }
    jar.sha256?.let { expected ->
      val actual = sha256Of(file)
      if (!actual.equals(expected, ignoreCase = true)) {
        throw IOException(
            "$projectLabel download checksum mismatch: expected $expected, got $actual"
        )
      }
    }
  }

  private fun sha256Of(file: File): String = fileDigestHex(file, "SHA-256")
}

package dev.paperplane.cli.plugins

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Thin client for the Modrinth v2 REST API — only the endpoints needed to resolve a plugin slug to
 * a downloadable, SHA256-verified JAR. Mirrors the style of [PaperDownloader]: java.net.http
 * client + Gson, no reactive or coroutine dependencies.
 *
 * The two hot calls:
 * - [resolveLatest] — "what's the newest version of `placeholderapi` compatible with MC 1.21.4 on
 *   Paper?" Returns null if no compatible version exists (so callers can render a helpful error
 *   with the set of available game versions).
 * - [resolveExact] — "give me version `1.7.3` of `vault` (still filtered to Paper)". Used for
 *   pinned entries. Returns null if that version doesn't exist or doesn't target Paper/Bukkit.
 *
 * Paper's loader also advertises Bukkit and Spigot plugins, so we accept the union `{paper, bukkit,
 * spigot}` on Modrinth's `loaders` filter — a pure-Bukkit plugin JAR still runs on Paper, and
 * Modrinth authors don't consistently tag `paper`.
 */
open class ModrinthClient(
    private val client: HttpClient = HttpClient.newHttpClient(),
    private val baseUrl: String = "https://api.modrinth.com/v2",
) {
  private val gson = Gson()

  /** Project metadata returned by `/project/{id_or_slug}`. */
  data class ProjectInfo(val slug: String, val title: String, val id: String)

  /**
   * Looks up a Modrinth project by either its slug ("worldedit") or its 8-char project ID
   * ("1u6JkXh5") — the API endpoint accepts both interchangeably. Used by `ppl plugin add` to
   * normalize user input: if a user pastes the "Copy ID" value from Modrinth's UI, we resolve it to
   * the canonical slug before writing to `paperplane.yml`, so the config never contains opaque IDs.
   * Throws [ModrinthNotFound] on 404.
   */
  open fun getProject(idOrSlug: String): ProjectInfo {
    val url = "$baseUrl/project/$idOrSlug"
    val body = fetchOrNotFound(url, idOrSlug)
    val obj = gson.fromJson(body, com.google.gson.JsonObject::class.java)
    return ProjectInfo(
        slug = obj.get("slug").asString,
        title = obj.get("title")?.asString ?: obj.get("slug").asString,
        id = obj.get("id").asString,
    )
  }

  data class ResolvedVersion(
      val slug: String,
      val versionNumber: String,
      val downloadUrl: String,
      val sha512: String,
      val filename: String,
  )

  /**
   * Lists all versions of [slug] that target Paper and the given [mcVersion], newest first. Returns
   * an empty list if the project exists but has no compatible versions.
   *
   * Throws [ModrinthNotFound] if the slug doesn't exist at all (HTTP 404).
   */
  open fun listCompatibleVersions(slug: String, mcVersion: String): List<ResolvedVersion> {
    val loaders = URLEncoder.encode("""["paper","bukkit","spigot"]""", Charsets.UTF_8)
    val gameVersions = URLEncoder.encode("""["$mcVersion"]""", Charsets.UTF_8)
    val url = "$baseUrl/project/$slug/version?loaders=$loaders&game_versions=$gameVersions"

    val body = fetchOrNotFound(url, slug)
    val versionsJson = gson.fromJson(body, JsonArray::class.java)
    // Modrinth returns versions newest-first by date_published. We trust that order.
    return versionsJson.mapNotNull { element ->
      val obj = element.asJsonObject
      toResolvedVersion(slug, obj)
    }
  }

  /**
   * Returns the newest version of [slug] compatible with [mcVersion] on Paper, or null if no
   * compatible version exists. Use [listCompatibleVersions] when the caller wants to show the user
   * what *is* available.
   */
  open fun resolveLatest(slug: String, mcVersion: String): ResolvedVersion? =
      listCompatibleVersions(slug, mcVersion).firstOrNull()

  /**
   * Fetches one specific version of [slug] by its `version_number` field (the user-visible "1.7.3"
   * kind of string, not Modrinth's internal version ID). Filtered to [mcVersion] + Paper loader, so
   * a version that exists but targets a different MC version is treated as unresolvable and returns
   * null.
   */
  open fun resolveExact(slug: String, version: String, mcVersion: String): ResolvedVersion? =
      listCompatibleVersions(slug, mcVersion).firstOrNull { it.versionNumber == version }

  private fun toResolvedVersion(slug: String, obj: JsonObject): ResolvedVersion? {
    val versionNumber = obj.get("version_number")?.asString ?: return null
    val fileObj = pickPrimaryFile(obj.getAsJsonArray("files")) ?: return null
    // Modrinth's hashes block consistently provides sha1 and sha512 but NOT sha256. Use sha512
    // — strong, always present, and more diff-friendly in lockfiles than sha1.
    val downloadUrl = fileObj.get("url")?.asString
    val filename = fileObj.get("filename")?.asString
    val sha512 = fileObj.getAsJsonObject("hashes")?.get("sha512")?.asString
    if (downloadUrl == null || filename == null || sha512 == null) return null
    return ResolvedVersion(slug, versionNumber, downloadUrl, sha512, filename)
  }

  /** Prefer the file flagged `primary: true`; fall back to the first file in the array. */
  private fun pickPrimaryFile(files: JsonArray?): JsonObject? {
    if (files == null) return null
    return files.firstOrNull { it.asJsonObject.get("primary")?.asBoolean == true }?.asJsonObject
        ?: files.firstOrNull()?.asJsonObject
  }

  private fun fetchOrNotFound(url: String, slug: String): String {
    val response = sendRequest(url)
    return checkResponse(response, url, slug)
  }

  private fun sendRequest(url: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", UA).build()
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: IOException) {
      throw ModrinthNetworkError(
          "Could not reach Modrinth: ${e.message ?: e.javaClass.simpleName}", e)
    }
  }

  private fun checkResponse(response: HttpResponse<String>, url: String, slug: String): String {
    if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND) {
      throw ModrinthNotFound("No Modrinth project found for '$slug'")
    }
    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
      throw IOException("Modrinth API request failed: HTTP ${response.statusCode()} for $url")
    }
    return response.body()
  }

  companion object {
    // Modrinth asks API clients to identify themselves. A descriptive UA also helps in rate-limit
    // diagnostics if paperplane ever gets flagged.
    private const val UA = "paperplane-cli (+https://github.com/botshashka/paperplane)"
  }
}

class ModrinthNotFound(message: String) : RuntimeException(message)

class ModrinthNetworkError(message: String, cause: Throwable) : RuntimeException(message, cause)

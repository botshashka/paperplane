package dev.paperplane.cli.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.paperplane.cli.Versions
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class PaperVersionResolver(
    private val client: HttpClient = HttpClient.newHttpClient(),
    private val baseUrl: String = "https://api.papermc.io/v2/projects/paper",
) {
  companion object {
    private const val TIMEOUT_SECONDS = 5L
  }

  private val gson = Gson()

  @Volatile private var cachedVersions: List<String>? = null

  fun resolveLatest(): String = fetchSupportedVersions().lastOrNull() ?: Versions.PAPER_FALLBACK

  /** Returns the last [count] supported stable Paper versions, latest last. */
  fun resolveRecent(count: Int = 3): List<String> {
    val supported = fetchSupportedVersions()
    return if (supported.isEmpty()) listOf(Versions.PAPER_FALLBACK) else supported.takeLast(count)
  }

  /**
   * Fetches and filters the supported stable Paper versions. Successful results are memoized for
   * the lifetime of this resolver instance so a single wizard run can call [resolveLatest] and
   * [resolveRecent] without a second HTTP round-trip. Failures are NOT cached — a transient network
   * blip should not permanently stick the resolver to fallback mode if the instance happens to be
   * reused.
   */
  private fun fetchSupportedVersions(): List<String> {
    cachedVersions?.let {
      return it
    }
    val result =
        try {
          val json = gson.fromJson(fetch(baseUrl), JsonObject::class.java)
          val versions = json.getAsJsonArray("versions").map { it.asString }
          versions.filter {
            Versions.apiVersion(it) in Versions.SUPPORTED_API_VERSIONS && !it.contains("-")
          }
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
          emptyList()
        }
    if (result.isNotEmpty()) cachedVersions = result
    return result
  }

  private fun fetch(url: String): String {
    val request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
      throw IOException("Paper API request failed: HTTP ${response.statusCode()} for $url")
    }
    return response.body()
  }
}

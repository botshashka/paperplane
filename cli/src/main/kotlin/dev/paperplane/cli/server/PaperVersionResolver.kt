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

  fun resolveLatest(): String {
    return try {
      val json = gson.fromJson(fetch(baseUrl), JsonObject::class.java)
      val versions = json.getAsJsonArray("versions").map { it.asString }
      val supported = versions.filter {
        Versions.apiVersion(it) in Versions.SUPPORTED_API_VERSIONS && !it.contains("-")
      }
      supported.lastOrNull() ?: Versions.PAPER_FALLBACK
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
      Versions.PAPER_FALLBACK
    }
  }

  /** Returns the last [count] supported stable Paper versions, latest last. */
  fun resolveRecent(count: Int = 3): List<String> {
    return try {
      val json = gson.fromJson(fetch(baseUrl), JsonObject::class.java)
      val versions = json.getAsJsonArray("versions").map { it.asString }
      val supported = versions.filter {
        Versions.apiVersion(it) in Versions.SUPPORTED_API_VERSIONS && !it.contains("-")
      }
      if (supported.isEmpty()) listOf(Versions.PAPER_FALLBACK) else supported.takeLast(count)
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
      listOf(Versions.PAPER_FALLBACK)
    }
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

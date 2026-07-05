package dev.paperplane.cli.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.paperplane.cli.Versions
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

open class VelocityDownloader(
    private val cacheDir: File,
    private val client: HttpClient = HttpClient.newHttpClient(),
    private val baseUrl: String = "https://fill.papermc.io/v3/projects/velocity",
) {
  private val gson = Gson()

  open fun download(version: String? = null): File {
    val resolvedVersion = version ?: latestVersion()
    val jarFile = File(cacheDir, "velocity-$resolvedVersion.jar")
    if (jarFile.exists()) {
      return jarFile
    }

    cacheDir.mkdirs()

    // Fill v3 exposes the newest build (and its ready-made download URL) at builds/latest.
    val latest = fetch("$baseUrl/versions/$resolvedVersion/builds/latest")
    val serverJar = parseLatestServerJar(latest)

    val request =
        HttpRequest.newBuilder()
            .uri(URI.create(serverJar.url))
            .header("User-Agent", Versions.userAgent())
            .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofFile(jarFile.toPath()))

    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
      jarFile.delete()
      throw IOException("Failed to download Velocity: HTTP ${response.statusCode()}")
    }

    return jarFile
  }

  /**
   * Resolves the latest stable Velocity version in the pinned major series (e.g. 3.x). Falls back
   * to the latest stable version overall, then to whatever the API reports last, so a series rename
   * never leaves us empty-handed.
   */
  fun latestVersion(): String {
    val json = gson.fromJson(fetch(baseUrl), JsonObject::class.java)
    val all =
        json.getAsJsonObject("versions").entrySet().flatMap { (_, list) ->
          list.asJsonArray.map { it.asString }
        }
    val stable = all.filter { !it.contains("-") }.sortedWith(Versions::compareVersions)
    val seriesPrefix = "${Versions.VELOCITY_SERIES}."
    return stable.lastOrNull { it.startsWith(seriesPrefix) } ?: stable.lastOrNull() ?: all.last()
  }

  /** Parses a Fill v3 `builds/latest` response into the server jar's name and download URL. */
  internal fun parseLatestServerJar(json: String): ServerJar {
    val build = gson.fromJson(json, JsonObject::class.java)
    val serverDefault = build.getAsJsonObject("downloads").getAsJsonObject("server:default")
    return ServerJar(
        name = serverDefault.get("name").asString,
        url = serverDefault.get("url").asString,
    )
  }

  private fun fetch(url: String): String {
    val request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", Versions.userAgent())
            .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
      throw IOException("Velocity API request failed: HTTP ${response.statusCode()} for $url")
    }
    return response.body()
  }
}

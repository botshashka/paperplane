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

/** The download coordinates for a single Paper build's server jar, parsed from a Fill v3 build. */
internal data class ServerJar(val name: String, val url: String)

open class PaperDownloader(
    private val cacheDir: File,
    private val client: HttpClient = HttpClient.newHttpClient(),
    private val baseUrl: String = "https://fill.papermc.io/v3/projects/paper",
) {
  private val gson = Gson()

  open fun download(mcVersion: String): File {
    val jarFile = File(cacheDir, "paper-$mcVersion.jar")
    if (jarFile.exists()) {
      return jarFile
    }

    cacheDir.mkdirs()

    // Fill v3 exposes the newest build (and its ready-made download URL) at builds/latest.
    val latest = fetch("$baseUrl/versions/$mcVersion/builds/latest")
    val serverJar = parseLatestServerJar(latest)

    val request =
        HttpRequest.newBuilder()
            .uri(URI.create(serverJar.url))
            .header("User-Agent", Versions.userAgent())
            .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofFile(jarFile.toPath()))

    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
      jarFile.delete()
      throw IOException("Failed to download Paper: HTTP ${response.statusCode()}")
    }

    return jarFile
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
      throw IOException("Paper API request failed: HTTP ${response.statusCode()} for $url")
    }
    return response.body()
  }
}

package dev.paperplane.cli.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class VelocityDownloader(private val cacheDir: File) {
  private val client = HttpClient.newHttpClient()
  private val gson = Gson()
  private val baseUrl = "https://api.papermc.io/v2/projects/velocity"

  fun download(version: String? = null): File {
    val resolvedVersion = version ?: latestVersion()
    val jarFile = File(cacheDir, "velocity-$resolvedVersion.jar")
    if (jarFile.exists()) {
      return jarFile
    }

    cacheDir.mkdirs()

    // Get latest build for this version
    val buildsUrl = "$baseUrl/versions/$resolvedVersion/builds"
    val buildsResponse = fetch(buildsUrl)
    val buildsJson = gson.fromJson(buildsResponse, JsonObject::class.java)
    val builds = buildsJson.getAsJsonArray("builds")
    val latestBuild = builds.last().asJsonObject
    val buildNumber = latestBuild.get("build").asInt
    val downloadName =
        latestBuild.getAsJsonObject("downloads").getAsJsonObject("application").get("name").asString

    // Download the jar
    val downloadUrl =
        "$baseUrl/versions/$resolvedVersion/builds/$buildNumber/downloads/$downloadName"

    val request = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofFile(jarFile.toPath()))

    if (response.statusCode() != 200) {
      jarFile.delete()
      throw RuntimeException("Failed to download Velocity: HTTP ${response.statusCode()}")
    }

    return jarFile
  }

  fun latestVersion(): String {
    val response = fetch(baseUrl)
    val json = gson.fromJson(response, JsonObject::class.java)
    val versions = json.getAsJsonArray("versions")
    return versions.last().asString
  }

  private fun fetch(url: String): String {
    val request = HttpRequest.newBuilder().uri(URI.create(url)).build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
      throw RuntimeException("Velocity API request failed: HTTP ${response.statusCode()} for $url")
    }
    return response.body()
  }
}

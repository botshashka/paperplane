package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.paperplane.cli.Versions
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.util.Platform
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class UpgradeCommand : CliktCommand(name = "upgrade") {
  companion object {
    private const val GITHUB_API =
        "https://api.github.com/repos/botshashka/paperplane/releases/latest"
  }

  override fun run() {
    val currentVersion = Versions.paperplaneVersion()
    TerminalUI.block {
      val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
      val gson = Gson()

      // Fetch latest release info
      val latestVersion =
          TerminalUI.spin("Checking for updates...") { fetchLatestVersion(client, gson) }

      if (latestVersion == null) {
        error("Could not determine latest version")
        return@block
      }

      if (latestVersion == currentVersion) {
        success("Already up to date (v$currentVersion)")
        return@block
      }

      // Download and extract the new version
      val ok =
          TerminalUI.spin("Downloading v$latestVersion...") {
            downloadAndExtract(client, latestVersion)
          }

      if (ok) {
        success("Updated ppl v$currentVersion → v$latestVersion")
      } else {
        error("Failed to download v$latestVersion")
      }
    }
  }

  private fun fetchLatestVersion(client: HttpClient, gson: Gson): String? {
    val request =
        HttpRequest.newBuilder()
            .uri(URI.create(GITHUB_API))
            .header("Accept", "application/vnd.github+json")
            .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != HttpURLConnection.HTTP_OK) return null
    val json = gson.fromJson(response.body(), JsonObject::class.java)
    return json.get("tag_name")?.asString?.removePrefix("v")?.ifEmpty { null }
  }

  private fun downloadAndExtract(client: HttpClient, version: String): Boolean {
    val downloadUrl =
        "https://github.com/botshashka/paperplane/releases/download/v$version/ppl-$version.zip"
    val request = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).build()
    val tmpZip = File.createTempFile("ppl-upgrade-", ".zip")
    try {
      val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmpZip.toPath()))
      if (response.statusCode() != HttpURLConnection.HTTP_OK) {
        tmpZip.delete()
        return false
      }

      Platform.extractZip(
          tmpZip,
          Platform.paperplaneHome,
          stripTopLevel = true,
          markExecutable = { name -> name.startsWith("bin/") && !Platform.isWindows },
      )
      return true
    } finally {
      tmpZip.delete()
    }
  }
}

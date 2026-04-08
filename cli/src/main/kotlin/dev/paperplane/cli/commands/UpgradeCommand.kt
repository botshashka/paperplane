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

open class UpgradeCommand(private val ui: TerminalUI) : CliktCommand(name = "upgrade") {
  companion object {
    private const val GITHUB_API =
        "https://api.github.com/repos/botshashka/paperplane/releases/latest"
  }

  override fun run() {
    val currentVersion = Versions.paperplaneVersion()
    ui.block {
      val latestVersion = ui.spin("Checking for updates...") { fetchLatestVersion() }

      if (latestVersion == null) {
        error("Could not determine latest version")
        return@block
      }

      if (latestVersion == currentVersion) {
        success("Already up to date (v$currentVersion)")
        return@block
      }

      val ok = ui.spin("Downloading v$latestVersion...") { downloadAndExtract(latestVersion) }

      if (ok) {
        success("Updated ppl v$currentVersion → v$latestVersion")
      } else {
        error("Failed to download v$latestVersion")
      }
    }
  }

  /**
   * Fetches the latest release tag from GitHub. Returns the version string with the leading "v"
   * stripped, or null if the request failed. Tests override to return scripted values without
   * touching the network.
   */
  protected open fun fetchLatestVersion(): String? {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    return fetchLatestVersionImpl(client, Gson())
  }

  /**
   * Downloads the release zip and extracts it into `~/.paperplane/`. Returns true on success. Tests
   * override to short-circuit without touching the network or filesystem.
   */
  protected open fun downloadAndExtract(version: String): Boolean {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    return downloadAndExtractImpl(client, version)
  }

  private fun fetchLatestVersionImpl(client: HttpClient, gson: Gson): String? {
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

  private fun downloadAndExtractImpl(client: HttpClient, version: String): Boolean {
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

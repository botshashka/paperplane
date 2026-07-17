package dev.paperplane.cli.server

import java.io.File
import java.net.http.HttpClient

open class PaperDownloader(
    private val cacheDir: File,
    private val client: HttpClient = HttpClient.newHttpClient(),
    private val baseUrl: String = "https://fill.papermc.io/v3/projects/paper",
) {
  private val fill = FillClient(client, "Paper")

  open fun download(mcVersion: String): File {
    val jarFile = File(cacheDir, "paper-$mcVersion.jar")
    if (jarFile.exists()) {
      return jarFile
    }

    // Fill v3 exposes the newest build (and its ready-made download URL) at builds/latest.
    val serverJar =
        fill.parseLatestServerJar(fill.fetch("$baseUrl/versions/$mcVersion/builds/latest"))
    return fill.downloadVerified(serverJar, cacheDir, "paper-$mcVersion.jar")
  }
}

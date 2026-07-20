package dev.paperplane.cli.server

import dev.paperplane.cli.Versions
import java.io.File
import java.net.http.HttpClient

open class VelocityDownloader(
    private val cacheDir: File,
    private val client: HttpClient = HttpClient.newHttpClient(),
    private val baseUrl: String = "https://fill.papermc.io/v3/projects/velocity",
) {
  private val fill = FillClient(client, "Velocity")

  open fun download(version: String? = null): File {
    val resolvedVersion = version ?: latestVersion()
    val jarFile = File(cacheDir, "velocity-$resolvedVersion.jar")
    if (jarFile.exists()) {
      return jarFile
    }

    // Fill v3 exposes the newest build (and its ready-made download URL) at builds/latest.
    val serverJar =
        fill.parseLatestServerJar(fill.fetch("$baseUrl/versions/$resolvedVersion/builds/latest"))
    return fill.downloadVerified(serverJar, cacheDir, "velocity-$resolvedVersion.jar")
  }

  /**
   * Resolves the latest stable Velocity version in the pinned major series (e.g. 3.x). Falls back
   * to the latest stable version overall, then to whatever the API reports last, so a series rename
   * never leaves us empty-handed.
   */
  fun latestVersion(): String {
    val all = fill.parseVersionsMap(fill.fetch(baseUrl))
    val stable = all.filter { !it.contains("-") }.sortedWith(Versions::compareVersions)
    val seriesPrefix = "${Versions.VELOCITY_SERIES}."
    return stable.lastOrNull { it.startsWith(seriesPrefix) } ?: stable.lastOrNull() ?: all.last()
  }
}

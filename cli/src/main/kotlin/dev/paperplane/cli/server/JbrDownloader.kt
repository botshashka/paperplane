package dev.paperplane.cli.server

import com.google.gson.Gson
import com.google.gson.JsonArray
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.util.Platform
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Downloads and caches JetBrains Runtime (JBR) for Level 3 HMR.
 *
 * JBR includes the DCEVM patch that allows structural class redefinition (new methods, fields,
 * interfaces) via Instrumentation.redefineClasses().
 *
 * Downloads from JetBrains cache-redirector with release info from GitHub API. Caches extracted JDK
 * in ~/.paperplane/jbr/{version}-{os}-{arch}/.
 */
class JbrDownloader(cacheDir: File? = null) {
  private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
  private val gson = Gson()
  private val cacheDir = cacheDir ?: File(Platform.paperplaneHome, "jbr")

  /**
   * Downloads JBR if not cached. Returns the path to the `java` binary.
   *
   * Three legitimately distinct failure modes (no release found / HTTP error / extracted but binary
   * not found) at three different stages of the procedure — collapsing them into a single throw
   * site would obscure where the failure originated.
   */
  @Suppress("ThrowsCount")
  fun download(jdkMajorVersion: String = "21"): File {
    val os = detectOs()
    val arch = detectArch()
    val jbrDir = File(cacheDir, "$jdkMajorVersion-$os-$arch")
    val javaBin = findJavaBin(jbrDir)
    if (javaBin != null) return javaBin

    cacheDir.mkdirs()

    // Find latest release for this JDK version from GitHub
    val release =
        findLatestRelease(jdkMajorVersion)
            ?: throw IOException("No JBR release found for JDK $jdkMajorVersion")

    val downloadUrl = buildDownloadUrl(release, os, arch)

    TerminalUI.spinSubstatus("${release.version} ($os-$arch)")

    // Download archive to temp file
    val ext = if (Platform.isWindows) ".zip" else ".tar.gz"
    val archive = File(cacheDir, "jbr-$jdkMajorVersion$ext")
    val request = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofFile(archive.toPath()))

    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
      archive.delete()
      throw IOException("Failed to download JBR: HTTP ${response.statusCode()} from $downloadUrl")
    }

    // Extract archive
    jbrDir.mkdirs()
    if (Platform.isWindows) {
      Platform.extractZip(archive, jbrDir)
    } else {
      extractTarGz(archive, jbrDir)
    }
    archive.delete()

    return findJavaBin(jbrDir)
        ?: throw IOException("JBR extracted but java binary not found in $jbrDir")
  }

  private fun findJavaBin(jbrDir: File): File? {
    if (!jbrDir.exists()) return null
    val binary = if (Platform.isWindows) "java.exe" else "java"
    val candidates = sequence {
      yield(File(jbrDir, "bin/$binary"))
      if (!Platform.isWindows) yield(File(jbrDir, "Contents/Home/bin/$binary"))
      val subdirs = jbrDir.listFiles { f -> f.isDirectory } ?: emptyArray()
      for (subdir in subdirs) {
        yield(File(subdir, "bin/$binary"))
        if (!Platform.isWindows) yield(File(subdir, "Contents/Home/bin/$binary"))
      }
    }
    return candidates.firstOrNull { it.exists() }
  }

  private data class JbrRelease(val version: String, val buildId: String)

  private fun findLatestRelease(jdkMajorVersion: String): JbrRelease? {
    val url = "https://api.github.com/repos/JetBrains/JetBrainsRuntime/releases?per_page=20"
    val request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
      throw IOException("GitHub API request failed: HTTP ${response.statusCode()}")
    }

    val releases = gson.fromJson(response.body(), JsonArray::class.java)
    for (release in releases) {
      val obj = release.asJsonObject
      val tagName = obj.get("tag_name")?.asString ?: continue
      // Tags look like: jbr-release-21.0.10b1163.110
      val match = Regex("""jbr-release-($jdkMajorVersion\.\d+\.\d+)b(\d+\.\d+)""").find(tagName)
      if (match != null) {
        val (version, build) = match.destructured
        return JbrRelease(version, "b$build")
      }
    }
    return null
  }

  private fun buildDownloadUrl(release: JbrRelease, os: String, arch: String): String {
    val ext = if (Platform.isWindows) ".zip" else ".tar.gz"
    val filename = "jbrsdk-${release.version}-$os-$arch-${release.buildId}$ext"
    return "https://cache-redirector.jetbrains.com/intellij-jbr/$filename"
  }

  private fun detectOs(): String {
    val osName = System.getProperty("os.name", "").lowercase()
    return when {
      osName.contains("mac") || osName.contains("darwin") -> "osx"
      osName.contains("linux") -> "linux"
      osName.contains("windows") -> "windows"
      else -> throw IOException("Unsupported OS: $osName")
    }
  }

  private fun detectArch(): String {
    val arch = System.getProperty("os.arch", "").lowercase()
    return when {
      arch == "aarch64" || arch == "arm64" -> "aarch64"
      arch == "amd64" || arch == "x86_64" -> "x64"
      else -> throw IOException("Unsupported architecture: $arch")
    }
  }

  /**
   * Extracts a .tar.gz file to the target directory. Uses the system `tar` command for simplicity
   * and correctness (handles symlinks, permissions, long paths).
   */
  private fun extractTarGz(tarball: File, targetDir: File) {
    val proc =
        ProcessBuilder("tar", "xzf", tarball.absolutePath, "-C", targetDir.absolutePath)
            .redirectErrorStream(true)
            .start()
    val output = proc.inputStream.bufferedReader().readText()
    val exitCode = proc.waitFor()
    if (exitCode != 0) {
      throw IOException("Failed to extract JBR tarball (exit code $exitCode): $output")
    }
  }
}

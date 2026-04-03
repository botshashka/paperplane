package dev.paperplane.cli.server

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.paperplane.cli.ui.TerminalUI
import java.io.BufferedInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream

/**
 * Downloads and caches JetBrains Runtime (JBR) for Level 3 HMR.
 *
 * JBR includes the DCEVM patch that allows structural class redefinition
 * (new methods, fields, interfaces) via Instrumentation.redefineClasses().
 *
 * Downloads from JetBrains cache-redirector with release info from GitHub API.
 * Caches extracted JDK in .paperplane/cache/jbr-{version}/.
 */
class JbrDownloader(private val cacheDir: File) {
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val gson = Gson()

    /**
     * Downloads JBR if not cached. Returns the path to the `java` binary.
     */
    fun download(jdkMajorVersion: String = "21"): File {
        val jbrDir = File(cacheDir, "jbr-$jdkMajorVersion")
        val javaBin = findJavaBin(jbrDir)
        if (javaBin != null) return javaBin

        cacheDir.mkdirs()

        // Find latest release for this JDK version from GitHub
        val release = findLatestRelease(jdkMajorVersion)
            ?: throw RuntimeException("No JBR release found for JDK $jdkMajorVersion")

        val os = detectOs()
        val arch = detectArch()
        val downloadUrl = buildDownloadUrl(release, os, arch)

        TerminalUI.spinSubstatus("Downloading JBR ${release.version} ($os-$arch)...")

        // Download tarball to temp file
        val tarball = File(cacheDir, "jbr-$jdkMajorVersion.tar.gz")
        val request = HttpRequest.newBuilder()
            .uri(URI.create(downloadUrl))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tarball.toPath()))

        if (response.statusCode() != 200) {
            tarball.delete()
            throw RuntimeException("Failed to download JBR: HTTP ${response.statusCode()} from $downloadUrl")
        }

        // Extract tarball
        TerminalUI.spinSubstatus("Extracting JBR...")
        jbrDir.mkdirs()
        extractTarGz(tarball, jbrDir)
        tarball.delete()

        return findJavaBin(jbrDir)
            ?: throw RuntimeException("JBR extracted but java binary not found in $jbrDir")
    }

    private fun findJavaBin(jbrDir: File): File? {
        if (!jbrDir.exists()) return null

        // macOS: Contents/Home/bin/java (inside a wrapper dir)
        // Linux: bin/java (inside a wrapper dir)
        // The tarball extracts to a subdirectory like jbrsdk-21.0.10-osx-aarch64-b1163.110/
        val candidates = listOf(
            // Direct bin
            File(jbrDir, "bin/java"),
            // macOS layout
            File(jbrDir, "Contents/Home/bin/java"),
        )

        for (candidate in candidates) {
            if (candidate.exists()) return candidate
        }

        // Search one level deep (tarball extracts to a subdirectory)
        val subdirs = jbrDir.listFiles { f -> f.isDirectory } ?: return null
        for (subdir in subdirs) {
            for (relPath in listOf("bin/java", "Contents/Home/bin/java")) {
                val candidate = File(subdir, relPath)
                if (candidate.exists()) return candidate
            }
        }

        return null
    }

    private data class JbrRelease(val version: String, val buildId: String)

    private fun findLatestRelease(jdkMajorVersion: String): JbrRelease? {
        val url = "https://api.github.com/repos/JetBrains/JetBrainsRuntime/releases?per_page=20"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("GitHub API request failed: HTTP ${response.statusCode()}")
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
        // Use jbrsdk (SDK, not JRE) — we need javac isn't required but full JDK is standard
        val filename = "jbrsdk-${release.version}-$os-$arch-${release.buildId}.tar.gz"
        return "https://cache-redirector.jetbrains.com/intellij-jbr/$filename"
    }

    private fun detectOs(): String {
        val osName = System.getProperty("os.name", "").lowercase()
        return when {
            osName.contains("mac") || osName.contains("darwin") -> "osx"
            osName.contains("linux") -> "linux"
            osName.contains("windows") -> "windows"
            else -> throw RuntimeException("Unsupported OS: $osName")
        }
    }

    private fun detectArch(): String {
        val arch = System.getProperty("os.arch", "").lowercase()
        return when {
            arch == "aarch64" || arch == "arm64" -> "aarch64"
            arch == "amd64" || arch == "x86_64" -> "x64"
            else -> throw RuntimeException("Unsupported architecture: $arch")
        }
    }

    /**
     * Extracts a .tar.gz file to the target directory.
     * Uses the system `tar` command for simplicity and correctness
     * (handles symlinks, permissions, long paths).
     */
    private fun extractTarGz(tarball: File, targetDir: File) {
        val proc = ProcessBuilder("tar", "xzf", tarball.absolutePath, "-C", targetDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("Failed to extract JBR tarball (exit code $exitCode): $output")
        }
    }
}

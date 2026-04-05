package dev.paperplane.cli.server

import dev.paperplane.cli.util.Platform
import java.io.File
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JbrDownloaderTest {

  @TempDir lateinit var tempDir: File

  private val binaryName = if (Platform.isWindows) "java.exe" else "java"

  private val os: String
    get() {
      val name = System.getProperty("os.name", "").lowercase()
      return when {
        name.contains("mac") || name.contains("darwin") -> "osx"
        name.contains("linux") -> "linux"
        name.contains("windows") -> "windows"
        else -> "linux"
      }
    }

  private val arch: String
    get() {
      val a = System.getProperty("os.arch", "").lowercase()
      return when {
        a == "aarch64" || a == "arm64" -> "aarch64"
        else -> "x64"
      }
    }

  /** Builds the cache directory path matching the new global format: {version}-{os}-{arch} */
  private fun jbrCacheDir(cacheDir: File, jdkVersion: String): File =
      File(cacheDir, "$jdkVersion-$os-$arch")

  // ── Cache hit: macOS layout (subdirectory with Contents/Home/bin/java) ──

  @Test
  fun `download returns cached java binary with macOS layout`() {
    val cacheDir = File(tempDir, "jbr")
    val jbrDir = jbrCacheDir(cacheDir, "21")
    val subdir = File(jbrDir, "jbrsdk-21.0.10-$os-$arch-b1163.110")
    val javaBin = File(subdir, "Contents/Home/bin/$binaryName")
    javaBin.parentFile.mkdirs()
    javaBin.writeText("#!/bin/sh\necho fake java")
    javaBin.setExecutable(true)

    val downloader = JbrDownloader(cacheDir)
    val result = downloader.download("21")

    assertEquals(javaBin.absolutePath, result.absolutePath)
  }

  // ── Cache hit: Linux layout (subdirectory with bin/java) ───────────

  @Test
  fun `download returns cached java binary with Linux layout`() {
    val cacheDir = File(tempDir, "jbr")
    val jbrDir = jbrCacheDir(cacheDir, "21")
    val subdir = File(jbrDir, "jbrsdk-21.0.10-$os-$arch-b1163.110")
    val javaBin = File(subdir, "bin/$binaryName")
    javaBin.parentFile.mkdirs()
    javaBin.writeText("#!/bin/sh\necho fake java")
    javaBin.setExecutable(true)

    val downloader = JbrDownloader(cacheDir)
    val result = downloader.download("21")

    assertEquals(javaBin.absolutePath, result.absolutePath)
  }

  // ── Cache hit: direct bin/java at top level ────────────────────────

  @Test
  fun `download returns cached java binary at direct bin path`() {
    val cacheDir = File(tempDir, "jbr")
    val jbrDir = jbrCacheDir(cacheDir, "21")
    val javaBin = File(jbrDir, "bin/$binaryName")
    javaBin.parentFile.mkdirs()
    javaBin.writeText("#!/bin/sh\necho fake java")
    javaBin.setExecutable(true)

    val downloader = JbrDownloader(cacheDir)
    val result = downloader.download("21")

    assertEquals(javaBin.absolutePath, result.absolutePath)
  }

  // ── Cache hit: direct Contents/Home/bin/java at top level ──────────

  @Test
  fun `download returns cached java binary at direct macOS path`() {
    val cacheDir = File(tempDir, "jbr")
    val jbrDir = jbrCacheDir(cacheDir, "21")
    val javaBin = File(jbrDir, "Contents/Home/bin/$binaryName")
    javaBin.parentFile.mkdirs()
    javaBin.writeText("#!/bin/sh\necho fake java")
    javaBin.setExecutable(true)

    val downloader = JbrDownloader(cacheDir)
    val result = downloader.download("21")

    assertEquals(javaBin.absolutePath, result.absolutePath)
  }

  // ── Cache hit doesn't trigger network ──────────────────────────────

  @Test
  fun `download with cached binary completes immediately without error`() {
    val cacheDir = File(tempDir, "jbr")
    val jbrDir = jbrCacheDir(cacheDir, "21")
    val javaBin = File(jbrDir, "bin/$binaryName")
    javaBin.parentFile.mkdirs()
    javaBin.writeText("#!/bin/sh\necho fake java")
    javaBin.setExecutable(true)

    val downloader = JbrDownloader(cacheDir)

    // Should return instantly from cache; no network error possible
    val result = downloader.download("21")
    assertTrue(result.exists())
  }

  // ── Version-specific cache directories ─────────────────────────────

  @Test
  fun `download uses version-specific cache directory`() {
    val cacheDir = File(tempDir, "jbr")

    // Cache for JDK 21
    val dir21 = jbrCacheDir(cacheDir, "21")
    val sub21 = File(dir21, "jbrsdk-21.0.10-$os-$arch-b1163.110")
    val java21 = File(sub21, "Contents/Home/bin/$binaryName")
    java21.parentFile.mkdirs()
    java21.writeText("java 21")
    java21.setExecutable(true)

    // Cache for JDK 17
    val dir17 = jbrCacheDir(cacheDir, "17")
    val sub17 = File(dir17, "jbrsdk-17.0.12-$os-$arch-b1000.50")
    val java17 = File(sub17, "Contents/Home/bin/$binaryName")
    java17.parentFile.mkdirs()
    java17.writeText("java 17")
    java17.setExecutable(true)

    val downloader = JbrDownloader(cacheDir)

    val result21 = downloader.download("21")
    val result17 = downloader.download("17")

    assertTrue(result21.absolutePath.contains("21-"))
    assertTrue(result17.absolutePath.contains("17-"))
    assertNotEquals(result21.absolutePath, result17.absolutePath)
  }

  // ── OS/arch detection doesn't throw ────────────────────────────────

  @Test
  fun `JbrDownloader instantiates without error on current platform`() {
    val cacheDir = File(tempDir, "jbr")
    assertDoesNotThrow { JbrDownloader(cacheDir) }
  }

  // ── Cache returns correct file (not a directory) ───────────────────

  @Test
  fun `cached java binary is a file not a directory`() {
    val cacheDir = File(tempDir, "jbr")
    val jbrDir = jbrCacheDir(cacheDir, "21")
    val javaBin = File(jbrDir, "bin/$binaryName")
    javaBin.parentFile.mkdirs()
    javaBin.writeText("fake")
    javaBin.setExecutable(true)

    val downloader = JbrDownloader(cacheDir)
    val result = downloader.download("21")

    assertTrue(result.isFile)
    assertFalse(result.isDirectory)
  }
}

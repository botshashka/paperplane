package dev.paperplane.cli.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JbrDownloaderTest {

    @TempDir
    lateinit var tempDir: File

    // ── Cache hit: macOS layout (subdirectory with Contents/Home/bin/java) ──

    @Test
    fun `download returns cached java binary with macOS layout`() {
        val cacheDir = File(tempDir, "cache")
        val jbrDir = File(cacheDir, "jbr-21/jbrsdk-21.0.10-osx-aarch64-b1163.110")
        val javaBin = File(jbrDir, "Contents/Home/bin/java")
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
        val cacheDir = File(tempDir, "cache")
        val jbrDir = File(cacheDir, "jbr-21/jbrsdk-21.0.10-linux-x64-b1163.110")
        val javaBin = File(jbrDir, "bin/java")
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
        val cacheDir = File(tempDir, "cache")
        val javaBin = File(cacheDir, "jbr-21/bin/java")
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
        val cacheDir = File(tempDir, "cache")
        val javaBin = File(cacheDir, "jbr-21/Contents/Home/bin/java")
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
        val cacheDir = File(tempDir, "cache")
        val javaBin = File(cacheDir, "jbr-21/bin/java")
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
        val cacheDir = File(tempDir, "cache")

        // Cache for JDK 21
        val java21 = File(cacheDir, "jbr-21/jbrsdk-21.0.10-osx-aarch64-b1163.110/Contents/Home/bin/java")
        java21.parentFile.mkdirs()
        java21.writeText("java 21")
        java21.setExecutable(true)

        // Cache for JDK 17
        val java17 = File(cacheDir, "jbr-17/jbrsdk-17.0.12-osx-aarch64-b1000.50/Contents/Home/bin/java")
        java17.parentFile.mkdirs()
        java17.writeText("java 17")
        java17.setExecutable(true)

        val downloader = JbrDownloader(cacheDir)

        val result21 = downloader.download("21")
        val result17 = downloader.download("17")

        assertTrue(result21.absolutePath.contains("jbr-21"))
        assertTrue(result17.absolutePath.contains("jbr-17"))
        assertNotEquals(result21.absolutePath, result17.absolutePath)
    }

    // ── OS/arch detection doesn't throw ────────────────────────────────

    @Test
    fun `JbrDownloader instantiates without error on current platform`() {
        val cacheDir = File(tempDir, "cache")
        assertDoesNotThrow { JbrDownloader(cacheDir) }
    }

    // ── Cache returns correct file (not a directory) ───────────────────

    @Test
    fun `cached java binary is a file not a directory`() {
        val cacheDir = File(tempDir, "cache")
        val javaBin = File(cacheDir, "jbr-21/bin/java")
        javaBin.parentFile.mkdirs()
        javaBin.writeText("fake")
        javaBin.setExecutable(true)

        val downloader = JbrDownloader(cacheDir)
        val result = downloader.download("21")

        assertTrue(result.isFile)
        assertFalse(result.isDirectory)
    }
}

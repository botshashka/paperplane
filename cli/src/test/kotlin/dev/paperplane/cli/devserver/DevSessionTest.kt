package dev.paperplane.cli.devserver

import dev.paperplane.cli.Versions
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.server.PaperDownloader
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DevSessionTest {

  @TempDir lateinit var tempDir: File

  private fun createSession(
      jbr: String = "off",
      serverVersion: String? = null,
  ): DevSession {
    val config =
        PaperPlaneConfig.load(tempDir).let { cfg ->
          cfg.copy(
              dev = cfg.dev.copy(jbr = jbr),
              server = cfg.server.copy(version = serverVersion),
          )
        }
    return DevSession(
        config = config,
        ppDir = tempDir,
        gradle = GradleBridge(tempDir),
        downloader = PaperDownloader(File(tempDir, "cache")),
        projectDir = tempDir,
    )
  }

  private fun metadata(paperApiVersion: String = "1.21.4") =
      ProjectMetadata(
          jarPath = "build/libs/test.jar",
          paperApiVersion = paperApiVersion,
          mainClass = "com.example.Main",
          pluginName = "TestPlugin",
          projectDir = tempDir.absolutePath,
          version = "1.0.0",
      )

  // ── formatDuration ──────────────────────────────────────────────────

  @Test
  fun `formatDuration returns milliseconds below threshold`() {
    val session = createSession()
    assertEquals("0ms", session.formatDuration(0))
    assertEquals("1ms", session.formatDuration(1))
    assertEquals("500ms", session.formatDuration(500))
    assertEquals("999ms", session.formatDuration(999))
  }

  @Test
  fun `formatDuration returns seconds at and above threshold`() {
    val session = createSession()
    assertEquals("1.0s", session.formatDuration(1000))
    assertEquals("1.5s", session.formatDuration(1500))
    assertEquals("2.0s", session.formatDuration(2000))
    assertEquals("10.0s", session.formatDuration(10_000))
  }

  @Test
  fun `formatDuration handles fractional seconds`() {
    val session = createSession()
    assertEquals("1.2s", session.formatDuration(1200))
    assertEquals("3.7s", session.formatDuration(3700))
  }

  // ── resolveJava ─────────────────────────────────────────────────────

  @Test
  fun `resolveJava with jbr off returns system java without jbr`() {
    val session = createSession(jbr = "off")
    val result = session.resolveJava()
    assertEquals("java", result.bin)
    assertFalse(result.isJbr)
  }

  @Test
  fun `resolveJava with custom path returns that path`() {
    val customPath = "/opt/custom/bin/java"
    val session = createSession(jbr = customPath)
    val result = session.resolveJava()
    assertEquals(customPath, result.bin)
    assertFalse(result.isJbr)
  }

  // ── formatDurationMs top-level function ────────────────────────────

  @Test
  fun `formatDurationMs matches DevSession formatDuration`() {
    val session = createSession()
    for (ms in listOf(0L, 1L, 500L, 999L, 1000L, 1500L, 5000L, 12345L)) {
      assertEquals(session.formatDuration(ms), formatDurationMs(ms))
    }
  }

  // ── resolveMcVersion ────────────────────────────────────────────────

  @Test
  fun `resolveMcVersion returns metadata version when no config override`() {
    val session = createSession()
    assertEquals("1.21.4", session.resolveMcVersion(metadata("1.21.4")))
  }

  @Test
  fun `resolveMcVersion returns config version when set`() {
    val session = createSession(serverVersion = "1.20.6")
    assertEquals("1.20.6", session.resolveMcVersion(metadata("1.21.4")))
  }

  @Test
  fun `resolveMcVersion accepts all supported api versions`() {
    val session = createSession()
    for (api in Versions.SUPPORTED_API_VERSIONS) {
      // Should not throw for any supported version
      session.resolveMcVersion(metadata(api))
    }
  }

  @Test
  fun `resolveMcVersion throws for unsupported version`() {
    val session = createSession()
    val ex =
        assertThrows(IllegalArgumentException::class.java) {
          session.resolveMcVersion(metadata("1.17.1"))
        }
    assertTrue(ex.message!!.contains("not supported"))
    assertTrue(ex.message!!.contains("1.17"))
  }

  @Test
  fun `resolveMcVersion throws for unsupported config override`() {
    val session = createSession(serverVersion = "1.16.5")
    val ex =
        assertThrows(IllegalArgumentException::class.java) {
          session.resolveMcVersion(metadata("1.21.4"))
        }
    assertTrue(ex.message!!.contains("1.16"))
  }

  // ── DevSession constants ──────────────────────────────────────────

  @Test
  fun `SERVER_PORT and SWAP_PORT are distinct`() {
    assertTrue(DevSession.SERVER_PORT != DevSession.SWAP_PORT)
  }

  @Test
  fun `SERVER_PORT and SWAP_PORT are in valid range`() {
    assertTrue(DevSession.SERVER_PORT in 1024..65535)
    assertTrue(DevSession.SWAP_PORT in 1024..65535)
  }
}

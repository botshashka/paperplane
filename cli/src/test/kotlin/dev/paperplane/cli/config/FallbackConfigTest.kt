package dev.paperplane.cli.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.github.ajalt.clikt.core.ProgramResult
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Parsing coverage for `dev.fallback`. Mirrors [LeakDiagnosticsConfigTest]'s structure: both enum
 * values, the default when the key is absent, and a malformed value.
 */
class FallbackConfigTest {

  @TempDir lateinit var tempDir: File
  private val ui = TerminalUI(RecordingTerminal())

  // ── DevConfig fallback field parsing ───────────────────────────────

  @Test
  fun `default DevConfig has fallback ask`() {
    assertEquals(FallbackPolicy.ASK, DevConfig().fallback)
  }

  @Test
  fun `DevConfig with fallback ask parses correctly`() {
    val config = Yaml.default.decodeFromString<DevConfig>("""fallback: ask""")
    assertEquals(FallbackPolicy.ASK, config.fallback)
  }

  @Test
  fun `DevConfig with fallback auto parses correctly`() {
    val config = Yaml.default.decodeFromString<DevConfig>("""fallback: auto""")
    assertEquals(FallbackPolicy.AUTO, config.fallback)
  }

  @Test
  fun `DevConfig without fallback defaults to ask`() {
    // Another key present, fallback absent — the default must fill in.
    val config = Yaml.default.decodeFromString<DevConfig>("""mode: hot-reload""")
    assertEquals(FallbackPolicy.ASK, config.fallback)
  }

  @Test
  fun `DevConfig with malformed fallback value is rejected`() {
    assertThrows<YamlException> { Yaml.default.decodeFromString<DevConfig>("""fallback: silent""") }
  }

  // ── Within the full config ─────────────────────────────────────────

  @Test
  fun `full config parses fallback alongside other dev fields`() {
    val yaml =
        """
        dev:
          mode: hot-reload
          fallback: auto
        """
            .trimIndent()
    val config = Yaml.default.decodeFromString<PaperPlaneConfig>(yaml)

    assertEquals(DevMode.HOT_RELOAD, config.dev.mode)
    assertEquals(FallbackPolicy.AUTO, config.dev.fallback)
  }

  // ── Config file loading ────────────────────────────────────────────

  @Test
  fun `PaperPlaneConfig load returns ask default when file missing`() {
    assertEquals(FallbackPolicy.ASK, PaperPlaneConfig.load(tempDir, ui).dev.fallback)
  }

  @Test
  fun `PaperPlaneConfig load reads fallback from file`() {
    File(tempDir, "paperplane.yml")
        .writeText(
            """
            dev:
              fallback: auto
            """
                .trimIndent()
        )
    assertEquals(FallbackPolicy.AUTO, PaperPlaneConfig.load(tempDir, ui).dev.fallback)
  }

  @Test
  fun `PaperPlaneConfig load rejects a malformed fallback value`() {
    File(tempDir, "paperplane.yml")
        .writeText(
            """
            dev:
              fallback: silent
            """
                .trimIndent()
        )
    // An invalid enum value surfaces as a program error, not a silent fallback-to-default.
    assertThrows<ProgramResult> { PaperPlaneConfig.load(tempDir, ui) }
  }

  // ── DevMode labels ─────────────────────────────────────────────────

  @Test
  fun `DevMode labels match their serial names`() {
    // The label feeds user-facing messages ("Switch this session to restart?") and must never
    // drift from the value users write under dev.mode.
    assertEquals("hot-reload", DevMode.HOT_RELOAD.label)
    assertEquals("blue-green", DevMode.BLUE_GREEN.label)
    assertEquals("restart", DevMode.RESTART.label)
  }
}

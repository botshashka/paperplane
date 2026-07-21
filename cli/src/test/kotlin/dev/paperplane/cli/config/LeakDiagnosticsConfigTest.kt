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
 * Parsing coverage for `dev.leak-diagnostics`. Mirrors [DevCommandJbrTest]'s structure: the enum's
 * three values, the default when the key is absent, and a malformed value.
 */
class LeakDiagnosticsConfigTest {

  @TempDir lateinit var tempDir: File
  private val ui = TerminalUI(RecordingTerminal())

  // ── DevConfig leakDiagnostics field parsing ────────────────────────

  @Test
  fun `default DevConfig has leakDiagnostics summary`() {
    assertEquals(LeakDiagnosticsMode.SUMMARY, DevConfig().leakDiagnostics)
  }

  @Test
  fun `DevConfig with leak-diagnostics summary parses correctly`() {
    val config = Yaml.default.decodeFromString<DevConfig>("""leak-diagnostics: summary""")
    assertEquals(LeakDiagnosticsMode.SUMMARY, config.leakDiagnostics)
  }

  @Test
  fun `DevConfig with leak-diagnostics full parses correctly`() {
    val config = Yaml.default.decodeFromString<DevConfig>("""leak-diagnostics: full""")
    assertEquals(LeakDiagnosticsMode.FULL, config.leakDiagnostics)
  }

  @Test
  fun `DevConfig with leak-diagnostics off parses correctly`() {
    val config = Yaml.default.decodeFromString<DevConfig>("""leak-diagnostics: off""")
    assertEquals(LeakDiagnosticsMode.OFF, config.leakDiagnostics)
  }

  @Test
  fun `DevConfig without leak-diagnostics defaults to summary`() {
    // Another key present, leak-diagnostics absent — the default must fill in.
    val config = Yaml.default.decodeFromString<DevConfig>("""mode: hot-reload""")
    assertEquals(LeakDiagnosticsMode.SUMMARY, config.leakDiagnostics)
  }

  @Test
  fun `DevConfig with malformed leak-diagnostics value is rejected`() {
    assertThrows<YamlException> {
      Yaml.default.decodeFromString<DevConfig>("""leak-diagnostics: verbose""")
    }
  }

  // ── DevConfig protocolLog field parsing ────────────────────────────

  @Test
  fun `default DevConfig has protocolLog false`() {
    assertEquals(false, DevConfig().protocolLog)
  }

  @Test
  fun `DevConfig with protocol-log true parses correctly`() {
    // A typo'd @SerialName would silently disable the tee, so lock the mapping down explicitly.
    val config = Yaml.default.decodeFromString<DevConfig>("""protocol-log: true""")
    assertEquals(true, config.protocolLog)
  }

  @Test
  fun `DevConfig without protocol-log defaults to false`() {
    val config = Yaml.default.decodeFromString<DevConfig>("""mode: hot-reload""")
    assertEquals(false, config.protocolLog)
  }

  // ── Within the full config ─────────────────────────────────────────

  @Test
  fun `full config parses leak-diagnostics alongside other dev fields`() {
    val yaml =
        """
        dev:
          mode: hot-reload
          leak-diagnostics: full
          protocol-log: true
          jbr: "on"
        """
            .trimIndent()
    val config = Yaml.default.decodeFromString<PaperPlaneConfig>(yaml)

    assertEquals(DevMode.HOT_RELOAD, config.dev.mode)
    assertEquals(LeakDiagnosticsMode.FULL, config.dev.leakDiagnostics)
    assertEquals(true, config.dev.protocolLog)
    assertEquals("on", config.dev.jbr)
  }

  // ── Config file loading ────────────────────────────────────────────

  @Test
  fun `PaperPlaneConfig load returns summary default when file missing`() {
    assertEquals(
        LeakDiagnosticsMode.SUMMARY,
        PaperPlaneConfig.load(tempDir, ui).dev.leakDiagnostics,
    )
  }

  @Test
  fun `PaperPlaneConfig load reads leak-diagnostics from file`() {
    File(tempDir, "paperplane.yml")
        .writeText(
            """
            dev:
              leak-diagnostics: off
            """
                .trimIndent()
        )
    assertEquals(LeakDiagnosticsMode.OFF, PaperPlaneConfig.load(tempDir, ui).dev.leakDiagnostics)
  }

  @Test
  fun `PaperPlaneConfig load rejects a malformed leak-diagnostics value`() {
    File(tempDir, "paperplane.yml")
        .writeText(
            """
            dev:
              leak-diagnostics: verbose
            """
                .trimIndent()
        )
    // An invalid enum value surfaces as a program error, not a silent fallback.
    assertThrows<ProgramResult> { PaperPlaneConfig.load(tempDir, ui) }
  }
}

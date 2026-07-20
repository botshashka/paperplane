package dev.paperplane.companion.host

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The companion's side of the leak-diagnostics config channel: parsing the wire value and reading
 * `.paperplane/companion-config.json`. Every degenerate input (missing file, malformed JSON,
 * unknown mode) must fall back to [LeakDiagnosticsMode.SUMMARY] and never throw — a config hiccup
 * can't be allowed to take the companion down.
 */
class LeakDiagnosticsModeTest {

  @TempDir lateinit var tempDir: File

  // ── fromWire ────────────────────────────────────────────────────────

  @Test
  fun `fromWire maps each known value`() {
    assertEquals(LeakDiagnosticsMode.OFF, LeakDiagnosticsMode.fromWire("off"))
    assertEquals(LeakDiagnosticsMode.SUMMARY, LeakDiagnosticsMode.fromWire("summary"))
    assertEquals(LeakDiagnosticsMode.FULL, LeakDiagnosticsMode.fromWire("full"))
  }

  @Test
  fun `fromWire is case-insensitive`() {
    assertEquals(LeakDiagnosticsMode.FULL, LeakDiagnosticsMode.fromWire("FULL"))
  }

  @Test
  fun `fromWire falls back to summary on null or unknown`() {
    assertEquals(LeakDiagnosticsMode.SUMMARY, LeakDiagnosticsMode.fromWire(null))
    assertEquals(LeakDiagnosticsMode.SUMMARY, LeakDiagnosticsMode.fromWire("verbose"))
    assertEquals(LeakDiagnosticsMode.SUMMARY, LeakDiagnosticsMode.fromWire(""))
  }

  // ── readFrom ────────────────────────────────────────────────────────

  @Test
  fun `readFrom parses the CLI-written shape`() {
    val file = write("""{"protocolVersion": 1, "leakDiagnostics": "full"}""")
    assertEquals(LeakDiagnosticsMode.FULL, LeakDiagnosticsMode.readFrom(file))
  }

  @Test
  fun `readFrom defaults to summary when the file is missing`() {
    assertEquals(
        LeakDiagnosticsMode.SUMMARY,
        LeakDiagnosticsMode.readFrom(File(tempDir, "does-not-exist.json")),
    )
  }

  @Test
  fun `readFrom defaults to summary on malformed json without throwing`() {
    assertEquals(
        LeakDiagnosticsMode.SUMMARY,
        LeakDiagnosticsMode.readFrom(write("{not valid json")),
    )
  }

  @Test
  fun `readFrom defaults to summary when the leakDiagnostics key is absent`() {
    assertEquals(
        LeakDiagnosticsMode.SUMMARY,
        LeakDiagnosticsMode.readFrom(write("""{"protocolVersion": 1}""")),
    )
  }

  @Test
  fun `readFrom defaults to summary on an unknown mode value`() {
    assertEquals(
        LeakDiagnosticsMode.SUMMARY,
        LeakDiagnosticsMode.readFrom(write("""{"leakDiagnostics": "loud"}""")),
    )
  }

  @Test
  fun `readFrom defaults to summary when leakDiagnostics is not a string`() {
    assertEquals(
        LeakDiagnosticsMode.SUMMARY,
        LeakDiagnosticsMode.readFrom(write("""{"leakDiagnostics": {"nested": true}}""")),
    )
  }

  private fun write(content: String): File =
      File(tempDir, "companion-config.json").apply { writeText(content) }
}

package dev.paperplane.companion.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The companion's side of the leak-diagnostics config channel: parsing the wire value carried on
 * the load request. Every degenerate input (null, unknown mode, wrong case) must fall back to
 * [LeakDiagnosticsMode.SUMMARY] and never throw — a config hiccup can't be allowed to take the
 * companion down.
 */
class LeakDiagnosticsModeTest {

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
}

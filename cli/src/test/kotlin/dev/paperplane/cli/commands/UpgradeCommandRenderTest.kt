package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.paperplane.cli.Versions
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Visual regression tests for [UpgradeCommand].
 *
 * Subclasses [UpgradeCommand] to override `fetchLatestVersion()` and `downloadAndExtract()` so no
 * real HTTP requests fire. Tests script the network responses by setting fields on the test
 * subclass before calling `parse(emptyList())`.
 */
class UpgradeCommandRenderTest {

  /** Test subclass with scriptable network results. */
  private class TestableUpgradeCommand(
      ui: TerminalUI,
      var nextVersion: String?,
      var nextDownloadResult: Boolean = true,
  ) : UpgradeCommand(ui) {
    override fun fetchLatestVersion(): String? = nextVersion

    override fun downloadAndExtract(version: String): Boolean = nextDownloadResult
  }

  private fun newCommand(
      nextVersion: String?,
      nextDownloadResult: Boolean = true,
  ): Pair<TestableUpgradeCommand, RecordingTerminal> {
    val terminal = RecordingTerminal()
    val ui = TerminalUI(terminal)
    return TestableUpgradeCommand(ui, nextVersion, nextDownloadResult) to terminal
  }

  // ── Already up to date ─────────────────────────────────────────────

  @Test
  fun `when latest version equals current prints already up to date`() {
    val current = Versions.paperplaneVersion()
    val (cmd, t) = newCommand(nextVersion = current)
    cmd.parse(emptyList())
    assertTrue(t.writes.any { it.contains("Already up to date") && it.contains("v$current") })
  }

  // ── Fetch failure ──────────────────────────────────────────────────

  @Test
  fun `null fetch result prints could not determine error`() {
    val (cmd, t) = newCommand(nextVersion = null)
    cmd.parse(emptyList())
    assertTrue(t.writes.any { it.contains("Could not determine latest version") })
  }

  // ── Successful upgrade ─────────────────────────────────────────────

  @Test
  fun `new version downloaded successfully prints upgrade summary`() {
    val current = Versions.paperplaneVersion()
    val (cmd, t) = newCommand(nextVersion = "$current.999", nextDownloadResult = true)
    cmd.parse(emptyList())
    assertTrue(t.writes.any { it.contains("Updated ppl") && it.contains("v$current") })
    assertTrue(t.writes.any { it.contains("$current.999") })
  }

  // ── Download failure ───────────────────────────────────────────────

  @Test
  fun `new version download failure prints failure error`() {
    val (cmd, t) = newCommand(nextVersion = "999.0.0", nextDownloadResult = false)
    cmd.parse(emptyList())
    assertTrue(t.writes.any { it.contains("Failed to download v999.0.0") })
  }
}

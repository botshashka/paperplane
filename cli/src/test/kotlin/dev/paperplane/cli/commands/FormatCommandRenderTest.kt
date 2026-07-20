package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.testing.FakeGradleBridge
import dev.paperplane.cli.testing.RenderTestBase
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Visual regression tests for [FormatCommand].
 *
 * Subclasses [FormatCommand] to inject a [FakeGradleBridge] via the protected `newGradleBridge()`
 * factory, so no real Spotless / Gradle daemon spawns. Tests script the [FakeGradleBridge]'s
 * `nextFormatResult` to drive each output branch.
 */
class FormatCommandRenderTest : RenderTestBase() {

  /** Test subclass that overrides newGradleBridge to return a configured fake. */
  private class TestableFormatCommand(
      ui: TerminalUI,
      private val fake: FakeGradleBridge,
  ) : FormatCommand(ui) {
    override fun newGradleBridge(): GradleBridge = fake
  }

  private fun newCommand(
      result: GradleBridge.FormatResult = GradleBridge.FormatResult(success = true)
  ): Triple<TestableFormatCommand, RecordingTerminal, FakeGradleBridge> {
    val (ui, terminal) = newUi()
    val fake = FakeGradleBridge(canonicalTempDir, ui, nextFormatResult = result)
    return Triple(TestableFormatCommand(ui, fake), terminal, fake)
  }

  // ── Happy paths ────────────────────────────────────────────────────

  @Test
  fun `successful apply prints Formatted`() {
    val (cmd, t, fake) = newCommand()
    cmd.parse(emptyList())
    assertTrue(t.writes.any { it.contains("Formatted") })
    assertTrue(fake.calls.contains("format(check=false)"))
  }

  @Test
  fun `successful check prints Formatting OK`() {
    val (cmd, t, fake) = newCommand()
    cmd.parse(listOf("--check"))
    assertTrue(t.writes.any { it.contains("Formatting OK") })
    assertTrue(fake.calls.contains("format(check=true)"))
  }

  // ── Task missing ───────────────────────────────────────────────────

  @Test
  fun `missing format task prints No formatter configured`() {
    val (cmd, t, _) = newCommand(GradleBridge.FormatResult(success = false, taskMissing = true))
    cmd.parse(emptyList())
    assertTrue(t.writes.any { it.contains("No formatter configured for this project") })
    assertTrue(t.writes.any { it.contains("Spotless") })
  }

  // ── Format failure with rootMessage ────────────────────────────────

  @Test
  fun `format failure with rootMessage shows the message and the spotless output`() {
    val (cmd, t, _) =
        newCommand(
            GradleBridge.FormatResult(
                success = false,
                rootMessage = "Build failed: bad token",
                outputLines = listOf("> Task :spotlessApply FAILED", "Error at line 5"),
            )
        )
    cmd.parse(emptyList())
    assertTrue(t.writes.any { it.contains("Build failed: bad token") })
    assertTrue(t.writes.any { it.contains("Spotless output") })
    assertTrue(t.writes.any { it.contains("Error at line 5") })
  }

  // ── Format failure with no rootMessage falls back ──────────────────

  @Test
  fun `format failure with no rootMessage falls back to Format failed`() {
    val (cmd, t, _) = newCommand(GradleBridge.FormatResult(success = false, rootMessage = null))
    cmd.parse(emptyList())
    assertTrue(t.writes.any { it.contains("Format failed") })
  }
}

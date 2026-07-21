package dev.paperplane.cli.devserver

import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [loadFailureHint] contract: maps each initial-load failure category to one short, actionable hint
 * (or null when the host's own message is self-explanatory).
 */
class LoadFailureHintTest {

  @Test
  fun `a plugin_yml-not-found failure points at the paperplane block`() {
    val hint = loadFailureHint(LoadWaitResult.Failed("plugin.yml not found", null))
    assertTrue(hint!!.contains("paperplane { } block in build.gradle.kts"))
  }

  @Test
  fun `plugin_yml matching is case-insensitive`() {
    val hint = loadFailureHint(LoadWaitResult.Failed("Malformed PLUGIN.YML entry", null))
    assertTrue(hint!!.contains("paperplane { } block"))
  }

  @Test
  fun `a paper-plugin_yml rejection does NOT get the paperplane-block hint`() {
    // The host's paper-plugin.yml message tells the user to switch dev modes; pointing at the
    // paperplane { } block (which generates a Bukkit plugin.yml) would be wrong advice.
    val hint =
        loadFailureHint(
            LoadWaitResult.Failed(
                "This is a Paper plugin (paper-plugin.yml), which hot-reload doesn't support yet.",
                null,
            )
        )
    assertTrue(hint == null || !hint.contains("paperplane { } block"))
  }

  @Test
  fun `a non-plugin_yml failure points at the server log`() {
    // A probe failure / onEnable exception surfaced as the host message — the detail is in the log.
    val hint =
        loadFailureHint(
            LoadWaitResult.Failed("SimplePluginManager.lookupNames field not found", null)
        )
    assertEquals("See the server log above for the plugin's load error.", hint)
  }

  @Test
  fun `a timeout points at the server log for load errors`() {
    val hint = loadFailureHint(LoadWaitResult.TimedOut)
    assertTrue(hint!!.contains("never reported back"))
    assertTrue(hint.contains("server log above"))
  }

  @Test
  fun `a server exit blames a static-init or onEnable crash`() {
    val hint = loadFailureHint(LoadWaitResult.ServerExited)
    assertTrue(hint!!.contains("static"))
    assertTrue(hint.contains("onEnable"))
  }

  @Test
  fun `a successful load has no hint`() {
    assertNull(loadFailureHint(LoadWaitResult.Ok(null)))
  }
}

/** [renderLeakWarnings] contract: one concise line when attributed, silence otherwise. */
class LoadReportTest {

  private val terminal = RecordingTerminal()
  private val ui = TerminalUI(terminal)

  @Test
  fun `null report renders nothing`() {
    ui.renderLeakWarnings(null)
    assertTrue(terminal.writes.isEmpty())
  }

  @Test
  fun `report without leaks renders nothing`() {
    ui.renderLeakWarnings(LoadReport(requestId = "r1", status = LoadStatus.OK))
    assertTrue(terminal.writes.isEmpty())
  }

  @Test
  fun `report with an empty attribution list renders nothing`() {
    ui.renderLeakWarnings(
        LoadReport(requestId = "r1", status = LoadStatus.OK, leaks = LeakSummary(consecutive = 1))
    )
    assertTrue(terminal.writes.isEmpty())
  }

  @Test
  fun `attributed leak renders a single warning line with the detail`() {
    ui.renderLeakWarnings(
        LoadReport(
            requestId = "r1",
            status = LoadStatus.OK,
            leaks =
                LeakSummary(
                    consecutive = 1,
                    attribution =
                        listOf(
                            LeakAttribution("thread", "thread 'MyPlugin-Timer' still running"),
                            LeakAttribution("scheduler", "secondary cause"),
                        ),
                ),
        )
    )

    assertEquals(1, terminal.writes.size, "exactly one concise line, not a diagnostic dump")
    assertTrue(terminal.writes.single().contains("thread 'MyPlugin-Timer' still running"))
  }
}

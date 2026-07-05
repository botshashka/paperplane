package dev.paperplane.cli.devserver

import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    ui.renderLeakWarnings(LoadReport(requestId = "r1", status = "ok"))
    assertTrue(terminal.writes.isEmpty())
  }

  @Test
  fun `report with an empty attribution list renders nothing`() {
    ui.renderLeakWarnings(
        LoadReport(requestId = "r1", status = "ok", leaks = LeakSummary(consecutive = 1))
    )
    assertTrue(terminal.writes.isEmpty())
  }

  @Test
  fun `attributed leak renders a single warning line with the detail`() {
    ui.renderLeakWarnings(
        LoadReport(
            requestId = "r1",
            status = "ok",
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

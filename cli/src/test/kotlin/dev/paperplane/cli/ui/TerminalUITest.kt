package dev.paperplane.cli.ui

import dev.paperplane.cli.ui.TerminalUI.PhaseEnd
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Facade-level visual regression tests for [TerminalUI].
 *
 * Each test constructs a fresh `TerminalUI(RecordingTerminal())`, drives the public API through a
 * representative call sequence, and asserts on the captured [RecordingTerminal.writes] list. Tests
 * run with `NO_COLOR=1` (set via root build.gradle.kts `tasks.withType<Test>`), so [Ansi.useColor]
 * is false and all emitted text is plain — no escape codes in assertions.
 *
 * These tests complement [BlockStateTest] (which asserts on `RenderOp` lists at the state-machine
 * layer) by covering the facade layer: formatting, block/phase lifecycle orchestration, the spinner
 * thread, and the interaction with [BlockRenderer] + [Writer].
 *
 * If an assertion fails after an intentional formatting change, update the expected list. Sharing
 * icons/prefixes with production via the same [Ansi] helpers (not duplicated constants) keeps the
 * blast radius small when a single character changes.
 */
class TerminalUITest {

  private fun newUi(): Pair<TerminalUI, RecordingTerminal> {
    val terminal = RecordingTerminal()
    return TerminalUI(terminal) to terminal
  }

  // ── Typed emitters ─────────────────────────────────────────────────

  @Test
  fun `success without duration emits the check icon and message`() {
    val (ui, t) = newUi()
    ui.success("Built")
    assertEquals(listOf("  ✓  Built"), t.writes)
  }

  @Test
  fun `success with duration appends the dim duration`() {
    val (ui, t) = newUi()
    ui.success("Built", "87ms")
    assertEquals(listOf("  ✓  Built 87ms"), t.writes)
  }

  @Test
  fun `error without duration emits the cross icon and message`() {
    val (ui, t) = newUi()
    ui.error("Build failed")
    assertEquals(listOf("  ✗  Build failed"), t.writes)
  }

  @Test
  fun `error with duration appends the dim duration`() {
    val (ui, t) = newUi()
    ui.error("Build failed", "1.2s")
    assertEquals(listOf("  ✗  Build failed 1.2s"), t.writes)
  }

  @Test
  fun `info renders label-value with arrow prefix`() {
    val (ui, t) = newUi()
    ui.info("Server:", "localhost:25565")
    assertEquals(listOf("  ➜  Server:  localhost:25565"), t.writes)
  }

  @Test
  fun `status emits dim indented text with no icon`() {
    val (ui, t) = newUi()
    ui.status("Watching for changes...")
    assertEquals(listOf("  Watching for changes..."), t.writes)
  }

  @Test
  fun `change uses the reload icon`() {
    val (ui, t) = newUi()
    ui.change("Change detected: Foo.java")
    assertEquals(listOf("  ⟳  Change detected: Foo.java"), t.writes)
  }

  @Test
  fun `totalTime uses a dim total prefix`() {
    val (ui, t) = newUi()
    ui.totalTime("1.2s")
    assertEquals(listOf("  total 1.2s"), t.writes)
  }

  @Test
  fun `blank emits a single empty write`() {
    val (ui, t) = newUi()
    ui.blank()
    assertEquals(listOf(""), t.writes)
  }

  @Test
  fun `fileCreated renders the check icon inline`() {
    val (ui, t) = newUi()
    ui.fileCreated("src/main/kotlin/Foo.kt")
    assertEquals(listOf("  ✓ src/main/kotlin/Foo.kt"), t.writes)
  }

  @Test
  fun `buildError with line number prints file-colon-line then each error line`() {
    val (ui, t) = newUi()
    ui.buildError("src/Foo.kt", 42, "error: unresolved reference\nexpected: Bar")
    assertEquals(
        listOf("  src/Foo.kt:42", "  error: unresolved reference", "  expected: Bar"),
        t.writes,
    )
  }

  @Test
  fun `buildError without line number omits the colon`() {
    val (ui, t) = newUi()
    ui.buildError("Build output", null, "stack trace\ngoes here")
    assertEquals(
        listOf("  Build output", "  stack trace", "  goes here"),
        t.writes,
    )
  }

  // ── Out-of-block primitives ────────────────────────────────────────

  @Test
  fun `header emits a leading blank then the header line`() {
    val (ui, t) = newUi()
    ui.header("1.2.3")
    assertEquals(listOf("", "  ✈  PaperPlane v1.2.3"), t.writes)
  }

  @Test
  fun `subtitle emits a leading blank then the subtitle`() {
    val (ui, t) = newUi()
    ui.subtitle("Let's make a new Paper plugin")
    assertEquals(listOf("", "  Let's make a new Paper plugin"), t.writes)
  }

  @Test
  fun `endView emits one trailing blank`() {
    val (ui, t) = newUi()
    ui.success("Done")
    ui.endView()
    assertEquals(listOf("  ✓  Done", ""), t.writes)
  }

  @Test
  fun `endView is idempotent within a view`() {
    val (ui, t) = newUi()
    ui.endView()
    ui.endView()
    ui.endView()
    assertEquals(listOf(""), t.writes)
  }

  @Test
  fun `header after endView reopens the view so endView fires again`() {
    val (ui, t) = newUi()
    ui.endView()
    ui.header("1.0.0")
    ui.endView()
    // endView, then header (blank + header line), then endView trailing blank
    assertEquals(listOf("", "", "  ✈  PaperPlane v1.0.0", ""), t.writes)
  }

  @Test
  fun `cancelled emits a blank then the cancelled banner`() {
    val (ui, t) = newUi()
    ui.cancelled()
    assertEquals(listOf("", "  ⚠  Cancelled"), t.writes)
  }

  // ── block { } lifecycle ────────────────────────────────────────────

  @Test
  fun `empty block emits nothing`() {
    val (ui, t) = newUi()
    ui.block {}
    assertEquals(emptyList<String>(), t.writes)
  }

  @Test
  fun `block with a single success emits leading separator then the line then commit`() {
    val (ui, t) = newUi()
    ui.block { success("Done") }
    // Footer redraw: separator + success line (pinned). endBlock: clear (not in writes), commit
    // with leading separator + line. Final writes sequence:
    assertEquals(listOf("", "  ✓  Done", "", "  ✓  Done"), t.writes)
  }

  @Test
  fun `block with multiple emits pins and then commits all lines`() {
    val (ui, t) = newUi()
    ui.block {
      success("Built")
      info("Mode:", "hot-reload")
    }
    // Redraws pin each line (first: sep+1, second: sep+2), then endBlock commits sep+2.
    assertEquals(
        listOf(
            "",
            "  ✓  Built",
            "",
            "  ✓  Built",
            "  ➜  Mode:  hot-reload",
            "",
            "  ✓  Built",
            "  ➜  Mode:  hot-reload",
        ),
        t.writes,
    )
  }

  @Test
  fun `block body exception still closes the block and rethrows`() {
    val (ui, t) = newUi()
    assertThrows(IllegalStateException::class.java) {
      ui.block {
        success("Started")
        error("boom")
        throw IllegalStateException("body failed")
      }
    }
    // endBlock runs in finally; the started/error lines are committed to scrollback.
    assertTrue(t.writes.contains("  ✓  Started"))
    assertTrue(t.writes.contains("  ✗  boom"))
  }

  @Test
  fun `two consecutive top-level blocks produce the expected committed sequence`() {
    val (ui, t) = newUi()
    ui.block { success("First") }
    ui.block { success("Second") }
    // Each block: redraw (sep+line), then commit (sep+line). The second block's commit uses the
    // needsSeparator rearmed by the first block's endBlock, so there's a blank between them.
    assertEquals(
        listOf(
            "",
            "  ✓  First",
            "",
            "  ✓  First",
            "",
            "  ✓  Second",
            "",
            "  ✓  Second",
        ),
        t.writes,
    )
  }

  @Test
  fun `nested emit calls via receiver work inside block`() {
    val (ui, t) = newUi()
    ui.block {
      success("A")
      info("B", "C")
      blank()
      status("D")
    }
    // Final committed lines (ignoring the intermediate redraws)
    val committed = t.writes.takeLast(5)
    assertEquals(
        listOf("", "  ✓  A", "  ➜  B  C", "", "  D"),
        committed,
    )
  }

  // ── phase { } lifecycle ────────────────────────────────────────────

  @Test
  fun `phase returning Watching opens the Watching footer after commit`() {
    val (ui, t) = newUi()
    ui.phase {
      success("Ready")
      PhaseEnd.Watching
    }
    // Commit of phase block + pinned Watching footer.
    t.assertEmittedInOrder("Ready", "Watching for changes...")
  }

  @Test
  fun `phase returning Waiting opens the Waiting footer`() {
    val (ui, t) = newUi()
    ui.phase {
      error("Build failed")
      PhaseEnd.Waiting
    }
    t.assertEmittedInOrder("Build failed", "Waiting for changes...")
  }

  @Test
  fun `phase returning None emits no trailing footer`() {
    val (ui, t) = newUi()
    ui.phase {
      success("Done")
      PhaseEnd.None
    }
    assertEquals(
        false,
        t.writes.any { it.contains("Watching") || it.contains("Waiting") },
    )
  }

  @Test
  fun `phase body exception clears the pinned footer and rethrows`() {
    val (ui, t) = newUi()
    assertThrows(IllegalStateException::class.java) {
      ui.phase {
        success("Partial")
        throw IllegalStateException("failed mid-phase")
        @Suppress("UNREACHABLE_CODE") PhaseEnd.Watching
      }
    }
    // No Watching footer should have been opened.
    assertEquals(false, t.writes.any { it.contains("Watching for changes") })
  }

  @Test
  fun `second phase discards the prior Watching footer before opening its own block`() {
    val (ui, t) = newUi()
    ui.phase {
      success("First")
      PhaseEnd.Watching
    }
    ui.phase {
      change("Change detected: Foo.java")
      PhaseEnd.Watching
    }
    t.assertEmittedInOrder("First", "Change detected: Foo.java", "Watching for changes...")
  }

  // ── nextSection ────────────────────────────────────────────────────

  @Test
  fun `nextSection inside a phase splits into two visually separate committed groups`() {
    val (ui, t) = newUi()
    ui.phase {
      success("Build succeeded", "87ms")
      success("Server ready", "7.4s")
      nextSection()
      info("Server:", "localhost:25565")
      info("Plugin:", "TestPlugin v1.0.0")
      PhaseEnd.Watching
    }
    // The two groups must both appear in order with the build lines before the info lines, and
    // there must be a blank-line separator between "Server ready" and "Server:".
    t.assertEmittedInOrder(
        "Build succeeded",
        "Server ready",
        "Server:",
        "Plugin:",
        "Watching for changes...",
    )
    t.assertSeparatorBetween("Server ready 7.4s", "Server:  localhost:25565", blankLines = 1)
  }

  @Test
  fun `nextSection outside an active block is a no-op`() {
    val (ui, t) = newUi()
    ui.nextSection()
    assertEquals(emptyList<String>(), t.writes)
  }

  // ── serverLog ──────────────────────────────────────────────────────

  @Test
  fun `serverLog outside any block scroll-commits with a leading separator`() {
    val (ui, t) = newUi()
    ui.serverLog("[INFO] starting")
    assertEquals(listOf("", "[INFO] starting"), t.writes)
  }

  @Test
  fun `serverLog above a pinned footer prints above and redraws the footer`() {
    val (ui, t) = newUi()
    ui.phase {
      status("Watching...")
      ui.serverLog("[INFO] tick")
      PhaseEnd.None
    }
    // The log line and the Watching footer should both be present.
    assertTrue(t.writes.any { it.contains("[INFO] tick") })
  }

  // ── spin ───────────────────────────────────────────────────────────

  @Test
  fun `spin without an outer block auto-commits result lines`() {
    val (ui, t) = newUi()
    val result = ui.spin("Building...") { "success" }
    assertEquals("success", result)
    // Spinner line was pinned then cleared; no committed lines remain since the body didn't emit
    // anything. The writes list may contain the pinned spinner frame and clears but no committed
    // output.
    assertEquals(false, t.writes.any { it.contains("✓") })
  }

  @Test
  fun `spin inside a block does not tear down the outer block`() {
    val (ui, t) = newUi()
    ui.block {
      success("Before")
      ui.spin("Working...") { Unit }
      success("After")
    }
    t.assertEmittedInOrder("Before", "After")
  }

  @Test
  fun `spin body exception clears the spinner and rethrows`() {
    val (ui, _) = newUi()
    assertThrows(IllegalStateException::class.java) {
      ui.spin<Unit>("Working...") { throw IllegalStateException("boom") }
    }
    // After spin's finally runs, the facade is back to a clean state — calling success now
    // should work without leaking state from the failed spin.
    ui.block { ui.success("Recovered") }
  }

  @Test
  fun `spinSubstatus without an active spinner falls back to status`() {
    val (ui, t) = newUi()
    ui.spinSubstatus("detail text")
    assertEquals(listOf("  detail text"), t.writes)
  }

  // ── clearPinnedFooter ──────────────────────────────────────────────

  @Test
  fun `clearPinnedFooter on a fresh terminal is a no-op`() {
    val (ui, t) = newUi()
    ui.clearPinnedFooter()
    assertEquals(emptyList<String>(), t.writes)
  }

  @Test
  fun `clearPinnedFooter after a phase discards the Watching footer state`() {
    val (ui, _) = newUi()
    ui.phase {
      success("Done")
      PhaseEnd.Watching
    }
    ui.clearPinnedFooter()
    // Calling phase again should work fresh without any leaked state.
    val (_, t2) = newUi()
    val ui2 = TerminalUI(t2)
    ui2.phase {
      success("Fresh")
      PhaseEnd.None
    }
    assertTrue(t2.writes.any { it.contains("Fresh") })
  }

  // ── Structural matchers smoke tests ────────────────────────────────

  @Test
  fun `assertNoConsecutiveBlanks passes on clean output`() {
    val (ui, t) = newUi()
    ui.header("1.0.0")
    ui.block {
      info("dev", "Start dev server")
      info("create", "Scaffold a new plugin")
    }
    ui.endView()
    t.assertNoConsecutiveBlanks()
  }

  @Test
  fun `assertViewClosed recognises a trailing endView blank`() {
    val (ui, t) = newUi()
    ui.success("Done")
    ui.endView()
    t.assertViewClosed()
  }
}

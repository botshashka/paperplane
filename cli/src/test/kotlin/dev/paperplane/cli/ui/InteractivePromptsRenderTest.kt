package dev.paperplane.cli.ui

import java.io.ByteArrayInputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Visual regression tests for the full [InteractivePrompts] rendering pipeline — `prompt()`,
 * `select()`, `confirm()`, the raw-mode lifecycle, and the cancellation paths.
 *
 * Drives prompts and selects end-to-end by passing a [FakeReader] (scripted code-point queue) into
 * a [RecordingTerminal], then asserts on the captured [RecordingTerminal.writes] for whole-line
 * output and the [RecordingTerminal.raw] string for partial-line ANSI sequences.
 *
 * `confirm()` and the non-TTY fallbacks read from `System.in` rather than the terminal, so those
 * tests redirect stdin via `System.setIn`.
 *
 * Tests run with `NO_COLOR=1` so all assertions read as plain text.
 *
 * Companion file [InteractivePromptsTest] still owns low-level keystroke unit tests on
 * `readPromptLine`. This file covers the next layer up: full prompt rendering, full select
 * rendering, lifecycle, and the regression cases for the spacing bugs we just debugged.
 */
class InteractivePromptsRenderTest {

  private val originalIn = System.`in`

  @AfterEach
  fun restoreStdin() {
    System.setIn(originalIn)
  }

  private fun stubStdin(input: String) {
    System.setIn(ByteArrayInputStream(input.toByteArray()))
  }

  private fun newPrompts(
      reader: FakeReader,
      isTty: Boolean = true,
  ): Pair<InteractivePrompts, RecordingTerminal> {
    val terminal = RecordingTerminal(isTty = isTty, scriptedReader = reader)
    return InteractivePrompts(terminal) to terminal
  }

  // ── prompt() ───────────────────────────────────────────────────────

  @Test
  fun `prompt with typed input commits the typed value`() {
    val (prompts, t) = newPrompts(FakeReader("hello\r".map { it.code }))
    val result = prompts.prompt("Plugin name", default = "My Plugin")
    assertEquals("hello", result)
    // Active state: blank, "  ›  Plugin name:" (the placeholder is a partial write, not in writes)
    // Committed state: "  ◇  Plugin name:" + "     hello"
    assertTrue(t.writes.contains("  ›  Plugin name:"))
    assertTrue(t.writes.contains("  ◇  Plugin name:"))
    assertTrue(t.writes.contains("     hello"))
  }

  @Test
  fun `prompt with Enter on default returns the default`() {
    val (prompts, t) = newPrompts(FakeReader(listOf('\r'.code)))
    val result = prompts.prompt("Author", default = "david")
    assertEquals("david", result)
    assertTrue(t.writes.contains("     david"))
  }

  @Test
  fun `prompt active state writes the placeholder via partial write to raw buffer`() {
    val (prompts, t) = newPrompts(FakeReader(listOf('\r'.code)))
    prompts.prompt("Author", default = "david")
    // The placeholder rendering "     david" goes through writer.write (partial-line) inside
    // renderPromptActive — it's in raw, not in writes (which only captures full writeLine calls).
    assertTrue(t.raw.contains("     david"))
  }

  @Test
  fun `prompt with empty input and no default re-prompts until non-empty`() {
    // First Enter has no default → re-renders. Second input "ok" + Enter commits.
    val (prompts, _) = newPrompts(FakeReader(listOf('\r'.code, 'o'.code, 'k'.code, '\r'.code)))
    val result = prompts.prompt("Project")
    assertEquals("ok", result)
  }

  @Test
  fun `prompt Ctrl+C throws PromptCancelledException with no extra blank in writes`() {
    val (prompts, t) = newPrompts(FakeReader(listOf('a'.code, 3)))
    assertThrows(PromptCancelledException::class.java) { prompts.prompt("Plugin name", "default") }
    // Active state was rendered. The cancel handler emits one writer.writeLine() (a blank) before
    // throwing, so writes contains that blank but not a committed-state line.
    assertFalse(t.writes.contains("  ◇  Plugin name:"))
  }

  @Test
  fun `non-TTY prompt fallback reads from stdin and prints inline default suffix`() {
    stubStdin("entered\n")
    val (prompts, t) = newPrompts(FakeReader(emptyList()), isTty = false)
    val result = prompts.prompt("Author", default = "david")
    assertEquals("entered", result)
    // Fallback writes "  Author (david): " as a partial write. Check via raw.
    assertTrue(t.raw.contains("Author"))
    assertTrue(t.raw.contains("(david)"))
  }

  @Test
  fun `non-TTY prompt fallback returns default on empty input`() {
    stubStdin("\n")
    val (prompts, _) = newPrompts(FakeReader(emptyList()), isTty = false)
    val result = prompts.prompt("Author", default = "david")
    assertEquals("david", result)
  }

  // ── select() ───────────────────────────────────────────────────────

  @Test
  fun `select with default index returns it on Enter`() {
    val (prompts, t) = newPrompts(FakeReader(listOf('\r'.code)))
    val idx = prompts.select("Language", listOf("Java", "Kotlin"), default = 1)
    assertEquals(1, idx)
    // Header rendered + collapsed committed view shows the chosen option label.
    assertTrue(t.writes.any { it.contains("Language") && it.contains("›") })
    assertTrue(t.writes.contains("     Kotlin"))
  }

  @Test
  fun `select arrow-down moves the cursor and Enter commits the new selection`() {
    // Down, down, Enter — from default 0 (Java) to index 2 (Restart)
    val keys = mutableListOf<Int>()
    keys.addAll(listOf(27, '['.code, 'B'.code)) // arrow down
    keys.addAll(listOf(27, '['.code, 'B'.code)) // arrow down
    keys.add('\r'.code)
    val (prompts, t) = newPrompts(FakeReader(keys.toList()))
    val idx = prompts.select("Dev mode", listOf("Hot reload", "Blue-green", "Restart"))
    assertEquals(2, idx)
    assertTrue(t.writes.contains("     Restart"))
  }

  @Test
  fun `select arrow-up wraps from first to last`() {
    val keys = listOf(27, '['.code, 'A'.code, '\r'.code) // up, Enter
    val (prompts, _) = newPrompts(FakeReader(keys))
    val idx = prompts.select("Lang", listOf("Java", "Kotlin", "Scala"))
    // From default 0, up wraps to last index 2
    assertEquals(2, idx)
  }

  @Test
  fun `select Ctrl+C throws PromptCancelledException without committing`() {
    val (prompts, t) = newPrompts(FakeReader(listOf(3)))
    assertThrows(PromptCancelledException::class.java) {
      prompts.select("Lang", listOf("Java", "Kotlin"))
    }
    // No committed-state ◇ marker for "Lang"
    assertFalse(t.writes.any { it.contains("◇") && it.contains("Lang") })
  }

  @Test
  fun `select bare ESC throws PromptCancelledException`() {
    val (prompts, _) = newPrompts(FakeReader(listOf(27))) // bare ESC, no follow-up
    assertThrows(PromptCancelledException::class.java) {
      prompts.select("Lang", listOf("Java", "Kotlin"))
    }
  }

  @Test
  fun `select header includes the optional note`() {
    val (prompts, t) = newPrompts(FakeReader(listOf('\r'.code)))
    prompts.select("Dev mode", listOf("Hot", "Cold"), note = "change anytime in paperplane.yml")
    assertTrue(t.writes.any { it.contains("Dev mode") && it.contains("change anytime") })
  }

  @Test
  fun `SelectOption description renders inline with em dash`() {
    val (prompts, t) = newPrompts(FakeReader(listOf('\r'.code)))
    prompts.select(
        "Mode",
        listOf(
            InteractivePrompts.SelectOption("Hot reload", "fastest iteration"),
            InteractivePrompts.SelectOption("Restart", "stop and start"),
        ),
    )
    // The option list goes through partial writes (raw), not writeLine. Check raw for the dash.
    assertTrue(t.raw.contains("— fastest iteration"))
  }

  @Test
  fun `non-TTY select fallback prints numbered list and reads stdin`() {
    stubStdin("2\n")
    val (prompts, t) = newPrompts(FakeReader(emptyList()), isTty = false)
    val idx = prompts.select("Lang", listOf("Java", "Kotlin"))
    assertEquals(1, idx)
    assertTrue(t.writes.any { it.contains("1. Java") })
    assertTrue(t.writes.any { it.contains("2. Kotlin") })
  }

  @Test
  fun `non-TTY select fallback returns default on empty input`() {
    stubStdin("\n")
    val (prompts, _) = newPrompts(FakeReader(emptyList()), isTty = false)
    val idx = prompts.select("Lang", listOf("Java", "Kotlin"), default = 1)
    assertEquals(1, idx)
  }

  // ── confirm() ──────────────────────────────────────────────────────

  @Test
  fun `confirm with y returns true`() {
    stubStdin("y\n")
    val (prompts, t) = newPrompts(FakeReader(emptyList()))
    val result = prompts.confirm("Are you sure?")
    assertTrue(result)
    assertTrue(t.raw.contains("Are you sure? (y/N)"))
  }

  @Test
  fun `confirm with yes returns true`() {
    stubStdin("yes\n")
    val (prompts, _) = newPrompts(FakeReader(emptyList()))
    assertTrue(prompts.confirm("Proceed?"))
  }

  @Test
  fun `confirm with n returns false`() {
    stubStdin("n\n")
    val (prompts, _) = newPrompts(FakeReader(emptyList()))
    assertFalse(prompts.confirm("Proceed?"))
  }

  @Test
  fun `confirm with empty input returns false`() {
    stubStdin("\n")
    val (prompts, _) = newPrompts(FakeReader(emptyList()))
    assertFalse(prompts.confirm("Proceed?"))
  }

  // ── Raw-mode lifecycle ─────────────────────────────────────────────

  @Test
  fun `beginInteractiveView enters raw mode exactly once`() {
    val (prompts, t) = newPrompts(FakeReader(emptyList()))
    prompts.beginInteractiveView()
    assertEquals(1, t.rawModeEntries)
  }

  @Test
  fun `beginInteractiveView is idempotent within an active view`() {
    val (prompts, t) = newPrompts(FakeReader(emptyList()))
    prompts.beginInteractiveView()
    prompts.beginInteractiveView()
    prompts.beginInteractiveView()
    assertEquals(1, t.rawModeEntries)
  }

  @Test
  fun `nested prompt inside an active interactive view does not re-enter raw mode`() {
    val (prompts, t) = newPrompts(FakeReader(listOf('\r'.code)))
    prompts.beginInteractiveView()
    prompts.prompt("Author", default = "david")
    // Only the outer beginInteractiveView entry — the prompt's withRawTty saw viewActive=true.
    assertEquals(1, t.rawModeEntries)
    prompts.endInteractiveView()
  }

  @Test
  fun `prompt outside an active view enters raw mode for its own duration`() {
    val (prompts, t) = newPrompts(FakeReader(listOf('\r'.code)))
    prompts.prompt("Author", default = "david")
    assertEquals(1, t.rawModeEntries)
  }

  @Test
  fun `endInteractiveView is idempotent`() {
    val (prompts, _) = newPrompts(FakeReader(emptyList()))
    prompts.beginInteractiveView()
    prompts.endInteractiveView()
    prompts.endInteractiveView()
    prompts.endInteractiveView()
    // No exception, no extra raw-mode entries.
  }

  @Test
  fun `restoreTerminalIfNeeded is a no-op when no view was opened`() {
    val (prompts, t) = newPrompts(FakeReader(emptyList()))
    prompts.restoreTerminalIfNeeded()
    assertEquals(0, t.rawModeEntries)
  }

  @Test
  fun `non-TTY beginInteractiveView never enters raw mode`() {
    val (prompts, t) = newPrompts(FakeReader(emptyList()), isTty = false)
    prompts.beginInteractiveView()
    assertEquals(0, t.rawModeEntries)
  }

  // ── Regression: spacing bugs we recently fixed ─────────────────────

  @Test
  fun `select Ctrl+C does not double-blank above the cancel position (regression)`() {
    // This is the bug we fixed by removing the writeLine after \u001b[?25h in the select cancel
    // handler. The select renders its option list (which goes to writeLine for some lines),
    // then on Ctrl+C the cursor stays on a fresh line below the options. If a future change
    // re-introduces a writer.writeLine() in the cancel handler, this test catches it by counting
    // blank lines emitted directly by the select before the throw.
    val (prompts, t) = newPrompts(FakeReader(listOf(3))) // immediate Ctrl+C
    assertThrows(PromptCancelledException::class.java) {
      prompts.select("Lang", listOf("Java", "Kotlin"))
    }
    val blanks = t.writes.count { it.isBlank() }
    // The select header itself emits one leading blank (renderSelectHeader). The cancel handler
    // must not emit another. Total expected blanks: 1.
    assertEquals(1, blanks, "Expected exactly 1 blank line (header lead-in), found $blanks")
  }

  @Test
  fun `prompt Ctrl+C emits exactly one blank from the cancel handler (regression)`() {
    // Mirror of the select test: prompt cancel still needs writer.writeLine() because the cursor
    // is mid-input-line. Verify the prompt's cancel emits exactly the expected blank count: one
    // leading blank from renderPromptActive + one from the cancel handler = 2.
    val (prompts, t) = newPrompts(FakeReader(listOf('a'.code, 3)))
    assertThrows(PromptCancelledException::class.java) { prompts.prompt("Plugin name", "default") }
    val blanks = t.writes.count { it.isBlank() }
    assertEquals(
        2,
        blanks,
        "Expected exactly 2 blanks (active-state lead-in + cancel-handler newline), found $blanks",
    )
  }

  // ── Smoke: rendered output integrates with TerminalUI cancelled banner ─

  @Test
  fun `select cancel followed by TerminalUI cancelled produces single blank between`() {
    // The full flow: a select gets cancelled, the caller catches PromptCancelledException and
    // calls TerminalUI.cancelled() which emits a blank + the banner. End-to-end: one blank
    // between the rendered option list and the cancel banner. Catches the recent bug.
    val terminal = RecordingTerminal(scriptedReader = FakeReader(listOf(3)))
    val ui = TerminalUI(terminal)
    val prompts = InteractivePrompts(terminal)
    assertThrows(PromptCancelledException::class.java) {
      prompts.select("Mode", listOf("A", "B", "C"))
    }
    ui.cancelled()
    // Find the indices of the last option list line and the cancelled banner.
    val cancelIdx = terminal.writes.indexOfFirst { it.contains("⚠") && it.contains("Cancelled") }
    assertNotEquals(-1, cancelIdx, "Cancelled banner missing")
    // Walk backward from the banner — the line just before should be a blank, and the one before
    // that must NOT also be a blank (no double-blank regression).
    assertTrue(
        terminal.writes[cancelIdx - 1].isBlank(),
        "Expected blank line directly above banner",
    )
    if (cancelIdx >= 2) {
      assertFalse(
          terminal.writes[cancelIdx - 2].isBlank(),
          "Found double blank before banner — the recent regression has returned",
      )
    }
  }
}

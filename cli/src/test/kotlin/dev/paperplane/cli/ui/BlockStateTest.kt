package dev.paperplane.cli.ui

import dev.paperplane.cli.ui.RenderOp.ClearFooter
import dev.paperplane.cli.ui.RenderOp.WriteLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BlockState] — the pure block/footer state machine.
 *
 * The whole point of the [BlockState] / [RenderOp] split is that these tests need no terminal, no
 * stdout capture, no ANSI regex matching. Just construct, drive transitions, assert on op lists.
 * Each test is one rule.
 *
 * `isTty = true` is the default — that's the interesting code path. The non-TTY case (no pinned
 * footer, no clear ops) gets one focused test at the bottom.
 */
class BlockStateTest {

  private fun newState(isTty: Boolean = true) = BlockState(isTty)

  // ── beginBlock / emit ──────────────────────────────────────────────

  @Test
  fun `beginBlock alone produces no ops`() {
    val state = newState()
    val ops = state.beginBlock(BlockState.BlockType.PERSIST)
    assertEquals(emptyList<RenderOp>(), ops)
    assertTrue(state.isBlockActive)
  }

  @Test
  fun `emit into PERSIST block writes the separator and the line as a pinned footer`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    val ops = state.emit("hello")
    // First emit: no prior footer, separator (needsSeparator starts true), one block line.
    assertEquals(listOf(WriteLine(), WriteLine("hello")), ops)
    assertEquals(2, state.pinnedLineCount)
  }

  @Test
  fun `second emit clears the prior footer before redrawing`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    state.emit("first")
    val ops = state.emit("second")
    // Clear the 2-line footer, redraw with separator + both lines.
    assertEquals(
        listOf(ClearFooter(2), WriteLine(), WriteLine("first"), WriteLine("second")),
        ops,
    )
    assertEquals(3, state.pinnedLineCount)
  }

  // ── endBlock ───────────────────────────────────────────────────────

  @Test
  fun `endBlock on PERSIST commits buffered lines and clears the footer`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    state.emit("hello")
    state.emit("world")
    val ops = state.endBlock()
    // Clear the 3-line footer (sep + 2 lines), then scroll-commit with the same separator-blank
    // and both lines. needsSeparator is consumed at commit time and rearmed for the next view.
    assertEquals(
        listOf(ClearFooter(3), WriteLine(), WriteLine("hello"), WriteLine("world")),
        ops,
    )
    assertFalse(state.isBlockActive)
    assertEquals(0, state.pinnedLineCount)
    assertTrue(state.separatorPending)
  }

  @Test
  fun `endBlock on TRANSIENT clears the footer silently without committing lines`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.TRANSIENT)
    state.emit("Watching for changes...")
    val ops = state.endBlock()
    assertEquals(listOf(ClearFooter(2)), ops)
    assertFalse(state.isBlockActive)
  }

  @Test
  fun `endBlock with no buffered content is a no-op`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    val ops = state.endBlock()
    assertEquals(emptyList<RenderOp>(), ops)
  }

  // ── Multi-line emit ────────────────────────────────────────────────

  @Test
  fun `emit splits multi-line text into separate block lines so footer math stays correct`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    val ops = state.emit("first\nsecond\nthird")
    // The redraw should pin 4 visible rows (1 separator + 3 lines), not 2.
    assertEquals(4, state.pinnedLineCount)
    assertEquals(
        listOf(WriteLine(), WriteLine("first"), WriteLine("second"), WriteLine("third")),
        ops,
    )
  }

  @Test
  fun `emit followed by another emit clears the right number of rows for multi-line content`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    state.emit("multi\nline\nmessage")
    val ops = state.emit("next")
    // ClearFooter must clear 4 rows (separator + 3 lines from the multi-line emit), not 2.
    // This is the regression fix for the dev-mode "ghost blank lines" bug.
    assertEquals(ClearFooter(4), ops.first())
  }

  @Test
  fun `emit drops trailing empty line from text ending in newline`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    state.emit("foo\n")
    // "foo\n".lines() is ["foo", ""] — the trailing empty string is dropped so we don't get
    // a phantom blank line at the end of the buffered content.
    assertEquals(listOf("foo"), state.bufferedLines)
  }

  @Test
  fun `emit preserves empty lines in the middle of multi-line text`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    state.emit("top\n\nbottom")
    assertEquals(listOf("top", "", "bottom"), state.bufferedLines)
  }

  @Test
  fun `non-TTY emit with multi-line text scroll-commits each line separately`() {
    val state = newState(isTty = false)
    val ops = state.emit("a\nb\nc")
    assertEquals(listOf(WriteLine("a"), WriteLine("b"), WriteLine("c")), ops)
  }

  // ── Wrap-aware visual row counting ─────────────────────────────────

  @Test
  fun `long line that wraps counts multiple visual rows in the pinned footer`() {
    // 80-col terminal, 160-character line → 2 visible rows for the line + 1 for separator = 3.
    val state = BlockState(isTty = true, widthProvider = { 80 })
    state.beginBlock(BlockState.BlockType.PERSIST)
    val longLine = "x".repeat(160)
    state.emit(longLine)
    assertEquals(3, state.pinnedLineCount)
  }

  @Test
  fun `next emit after a wrapped line clears the correct number of rows`() {
    val state = BlockState(isTty = true, widthProvider = { 80 })
    state.beginBlock(BlockState.BlockType.PERSIST)
    state.emit("x".repeat(160)) // 2 visual rows + 1 sep = 3
    val ops = state.emit("y") // should clear 3 rows, not 2
    assertEquals(ClearFooter(3), ops.first())
  }

  @Test
  fun `ANSI escape sequences are stripped before visual row counting`() {
    // ANSI escapes don't consume visible columns. A 60-char line with 20 chars of ANSI
    // escapes should count as 40 visible chars → fits on a single row at 80 cols.
    val state = BlockState(isTty = true, widthProvider = { 80 })
    state.beginBlock(BlockState.BlockType.PERSIST)
    val withEscapes = "\u001b[32m" + "x".repeat(40) + "\u001b[0m" + "y".repeat(20)
    state.emit(withEscapes)
    // 60 visible chars at 80 width = 1 row + 1 separator = 2.
    assertEquals(2, state.pinnedLineCount)
  }

  @Test
  fun `visualRows helper returns 1 for empty and short strings`() {
    assertEquals(1, BlockState.visualRows("", 80))
    assertEquals(1, BlockState.visualRows("short", 80))
    assertEquals(1, BlockState.visualRows("x".repeat(80), 80))
  }

  @Test
  fun `visualRows helper handles exact-width boundary`() {
    assertEquals(1, BlockState.visualRows("x".repeat(80), 80))
    assertEquals(2, BlockState.visualRows("x".repeat(81), 80))
    assertEquals(2, BlockState.visualRows("x".repeat(160), 80))
    assertEquals(3, BlockState.visualRows("x".repeat(161), 80))
  }

  // ── nextSection ────────────────────────────────────────────────────

  @Test
  fun `nextSection commits the current group and opens a fresh sub-block of the same type`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    state.emit("first")
    state.emit("second")
    val ops = state.nextSection()
    // Clear the pinned footer, scroll-commit "first" and "second" with a leading separator,
    // then we're left in a fresh PERSIST block with no pinned content.
    assertEquals(
        listOf(ClearFooter(3), WriteLine(), WriteLine("first"), WriteLine("second")),
        ops,
    )
    assertTrue(state.isBlockActive)
    assertEquals(0, state.pinnedLineCount)
    assertEquals(emptyList<String>(), state.bufferedLines)
  }

  @Test
  fun `nextSection followed by emit pins the new group as its own footer`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    state.emit("first")
    state.nextSection()
    val ops = state.emit("second-group")
    // Fresh sub-block: separator (needsSeparator was rearmed by nextSection's endBlock) plus the
    // line. The "first" group is already in scrollback and not part of this redraw.
    assertEquals(listOf(WriteLine(), WriteLine("second-group")), ops)
  }

  @Test
  fun `nextSection with no active block is a no-op`() {
    val state = newState()
    val ops = state.nextSection()
    assertEquals(emptyList<RenderOp>(), ops)
    assertFalse(state.isBlockActive)
  }

  // ── discardBlock ───────────────────────────────────────────────────

  @Test
  fun `discardBlock erases the footer without committing PERSIST lines`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    state.emit("transient")
    val ops = state.discardBlock()
    assertEquals(listOf(ClearFooter(2)), ops)
    assertFalse(state.isBlockActive)
  }

  // ── serverLog ──────────────────────────────────────────────────────

  @Test
  fun `serverLog with no active block scroll-commits with a leading separator`() {
    val state = newState()
    val ops = state.serverLog("[INFO] starting")
    assertEquals(listOf(WriteLine(), WriteLine("[INFO] starting")), ops)
  }

  @Test
  fun `serverLog above pinned footer clears, prints log, and redraws footer`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    state.emit("status")
    val ops = state.serverLog("[INFO] tick")
    // Clear the pinned footer (sep + 1 line = 2). It's the first log line and a separator is
    // pending → emit blank, then the log line. Then redraw the footer with hasLogOutput so the
    // separator-above-footer fires again.
    assertEquals(
        listOf(
            ClearFooter(2),
            WriteLine(),
            WriteLine("[INFO] tick"),
            WriteLine(),
            WriteLine("status"),
        ),
        ops,
    )
  }

  // ── header / subtitle / endView / cancelled ────────────────────────

  @Test
  fun `header emits blank then header line and reopens the view`() {
    val state = newState()
    val ops = state.header("PaperPlane v1.0")
    assertEquals(listOf(WriteLine(), WriteLine("PaperPlane v1.0")), ops)
    assertFalse(state.isViewClosed)
    assertTrue(state.separatorPending)
  }

  @Test
  fun `endView prints one trailing blank and is idempotent`() {
    val state = newState()
    val first = state.endView()
    val second = state.endView()
    assertEquals(listOf(WriteLine()), first)
    assertEquals(emptyList<RenderOp>(), second)
    assertTrue(state.isViewClosed)
  }

  @Test
  fun `header after endView reopens the view so endView fires again`() {
    val state = newState()
    state.endView()
    state.header("PaperPlane v1.0")
    val ops = state.endView()
    assertEquals(listOf(WriteLine()), ops)
  }

  @Test
  fun `cancelled banner prints blank then the formatted line`() {
    val state = newState()
    val ops = state.cancelled("  ⚠  Cancelled")
    assertEquals(listOf(WriteLine(), WriteLine("  ⚠  Cancelled")), ops)
  }

  // ── Spinner ────────────────────────────────────────────────────────

  @Test
  fun `setSpinner inside an active block redraws the footer with a spinner line`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    state.emit("working")
    val ops = state.setSpinner("building", null)
    // Clear current footer (sep+line=2), redraw with sep + line + spinner line (3 lines).
    assertEquals(4, ops.size)
    assertEquals(ClearFooter(2), ops[0])
    assertEquals(WriteLine(), ops[1])
    assertEquals(WriteLine("working"), ops[2])
    assertTrue(ops[3] is WriteLine && (ops[3] as WriteLine).text.contains("building"))
    assertEquals(3, state.pinnedLineCount)
  }

  @Test
  fun `tickSpinner advances frame and redraws without mutating buffered lines`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    state.emit("working")
    state.setSpinner("building", null)
    val before = state.bufferedLines
    val ops = state.tickSpinner()
    assertEquals(before, state.bufferedLines)
    // Should produce a fresh redraw cycle (clear + sep + line + spinner).
    assertEquals(4, ops.size)
    assertTrue(ops[0] is ClearFooter)
  }

  @Test
  fun `tickSpinner with no spinner is a no-op`() {
    val state = newState()
    val ops = state.tickSpinner()
    assertEquals(emptyList<RenderOp>(), ops)
  }

  @Test
  fun `clearSpinner erases the footer without committing lines`() {
    val state = newState()
    state.beginBlock(BlockState.BlockType.PERSIST)
    state.emit("working")
    state.setSpinner("building", null)
    val ops = state.clearSpinner()
    assertEquals(listOf(ClearFooter(3)), ops)
    assertEquals(0, state.pinnedLineCount)
    // Block remains active so a follow-up endBlock can commit the buffered line.
    assertTrue(state.isBlockActive)
    val commitOps = state.endBlock()
    // needsSeparator is still true (only consumed at commit time), so the commit emits a
    // leading blank then the buffered line.
    assertEquals(listOf(WriteLine(), WriteLine("working")), commitOps)
  }

  // ── Non-TTY ────────────────────────────────────────────────────────

  @Test
  fun `non-TTY emit buffers without producing ops until endBlock flushes`() {
    val state = newState(isTty = false)
    state.beginBlock(BlockState.BlockType.PERSIST)
    val first = state.emit("hello")
    val second = state.emit("world")
    // Non-TTY emits hold their content; nothing renders until endBlock.
    assertEquals(emptyList<RenderOp>(), first)
    assertEquals(emptyList<RenderOp>(), second)
    assertEquals(0, state.pinnedLineCount)
  }

  @Test
  fun `non-TTY endBlock flushes buffered lines with leading separator`() {
    val state = newState(isTty = false)
    state.beginBlock(BlockState.BlockType.PERSIST)
    state.emit("hello")
    state.emit("world")
    val ops = state.endBlock()
    assertEquals(
        listOf(WriteLine(), WriteLine("hello"), WriteLine("world")),
        ops,
    )
  }

  @Test
  fun `non-TTY emit outside a block scroll-commits directly`() {
    val state = newState(isTty = false)
    val ops = state.emit("standalone")
    assertEquals(listOf(WriteLine("standalone")), ops)
  }
}

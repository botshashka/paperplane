package dev.paperplane.cli.ui

import dev.paperplane.cli.ui.RenderOp.ClearFooter
import dev.paperplane.cli.ui.RenderOp.WriteLine

/**
 * Pure block/footer state machine — no I/O, no concurrency primitives.
 *
 * [TerminalUI] holds an instance, takes a lock, calls a transition (e.g. [emit], [endBlock],
 * [serverLog]), and hands the returned `List<RenderOp>` to a [BlockRenderer]. Splitting it out
 * makes the rules unit-testable without stdout capture: tests construct a `BlockState`, drive
 * transitions, and assert on op sequences.
 *
 * Single-threaded by contract — the caller (currently [TerminalUI]) is responsible for any locking.
 * The class is internally mutable.
 *
 * The two block types ([BlockType.PERSIST] and [BlockType.TRANSIENT]) drive the same rules as
 * before the extraction:
 * - PERSIST blocks commit their lines to the scrollback when [endBlock] is called.
 * - TRANSIENT blocks (the dev-server "Watching for changes..." footer) are erased silently.
 */
internal class BlockState(private val isTty: Boolean) {
  enum class BlockType {
    PERSIST,
    TRANSIENT,
  }

  // ── State ──────────────────────────────────────────────────────────
  // Visible to tests via the package-private accessors below; mutated only by transitions here.

  private var blockActive = false
  private var currentBlockType = BlockType.PERSIST
  private val blockLines = mutableListOf<String>()
  private var displayedLineCount = 0
  private var spinnerMessage: String? = null
  private var spinnerSubstatus: String? = null
  private var spinnerFrameIndex = 0
  private var needsSeparator = true
  private var hasLogOutput = false
  private var viewClosed = false

  // Test introspection ------------------------------------------------
  internal val isBlockActive: Boolean
    get() = blockActive

  internal val pinnedLineCount: Int
    get() = displayedLineCount

  internal val bufferedLines: List<String>
    get() = blockLines.toList()

  internal val isViewClosed: Boolean
    get() = viewClosed

  internal val separatorPending: Boolean
    get() = needsSeparator

  // ── Block lifecycle ────────────────────────────────────────────────

  /** Marks a new block open. Pure state change — no I/O until lines are emitted into it. */
  fun beginBlock(type: BlockType): List<RenderOp> {
    blockActive = true
    currentBlockType = type
    hasLogOutput = false
    return emptyList()
  }

  /**
   * Closes the current block. PERSIST blocks with content emit [ClearFooter] (to erase any pinned
   * redraw of those lines) followed by [WriteLine]s that scroll-commit them. TRANSIENT blocks just
   * clear silently.
   */
  fun endBlock(): List<RenderOp> {
    if (!blockActive && blockLines.isEmpty()) return emptyList()
    val ops = mutableListOf<RenderOp>()
    if (isTty && displayedLineCount > 0) {
      ops += ClearFooter(displayedLineCount)
      displayedLineCount = 0
    }
    if (currentBlockType == BlockType.PERSIST && blockLines.isNotEmpty()) {
      if (needsSeparator) {
        ops += WriteLine()
        needsSeparator = false
      }
      for (line in blockLines) ops += WriteLine(line)
      needsSeparator = true
    }
    resetBlock()
    return ops
  }

  /**
   * Commits the current block (as if [endBlock] were called) and immediately reopens a new block of
   * the same type. Used to insert a section boundary inside a `phase { }` body so two visually
   * separate groups of lines can share a single phase lifecycle.
   *
   * Crucially, the *prior* group is promoted to permanent scrollback before the new sub-block opens
   * — so if the phase body later throws, only the second group is discarded. Compared to inserting
   * a [blank] inside one block, this is the discard-safe choice. No-op when no block is active.
   */
  fun nextSection(): List<RenderOp> {
    if (!blockActive) return emptyList()
    val type = currentBlockType
    return endBlock() + beginBlock(type)
  }

  /** Discards the current block without committing PERSIST lines. Used by [phase] re-entry. */
  fun discardBlock(): List<RenderOp> {
    if (!blockActive && blockLines.isEmpty()) return emptyList()
    val ops = mutableListOf<RenderOp>()
    if (isTty && displayedLineCount > 0) {
      ops += ClearFooter(displayedLineCount)
      displayedLineCount = 0
    }
    resetBlock()
    return ops
  }

  // ── Output ─────────────────────────────────────────────────────────

  /**
   * Adds [text] to the active block. In TTY mode the pinned footer is redrawn so the buffered lines
   * stay visible; in non-TTY mode the lines are held until [endBlock] flushes them. Calls outside
   * any block scroll-commit directly.
   */
  fun emit(text: String): List<RenderOp> {
    if (blockActive) {
      blockLines.add(text)
      return if (isTty) redraw() else emptyList()
    }
    return listOf(WriteLine(text))
  }

  /**
   * Prints a server/proxy log line. When a footer is pinned, clears it, prints the log line in the
   * scrollback, then redraws the footer above so the pinned content stays at the bottom.
   */
  fun serverLog(line: String): List<RenderOp> {
    val ops = mutableListOf<RenderOp>()
    if (blockActive && isTty && displayedLineCount > 0) {
      val firstLog = !hasLogOutput
      hasLogOutput = true
      ops += ClearFooter(displayedLineCount)
      displayedLineCount = 0
      if (firstLog && needsSeparator) {
        ops += WriteLine()
        needsSeparator = false
      }
      ops += WriteLine(line)
      needsSeparator = true
      ops += redraw()
    } else {
      if (!hasLogOutput && needsSeparator) {
        ops += WriteLine()
        needsSeparator = false
      }
      hasLogOutput = true
      ops += WriteLine(line)
      needsSeparator = true
    }
    return ops
  }

  // ── Out-of-block primitives (header, subtitle, endView, cancelled) ──
  //
  // These bypass the block system — they print directly and only manipulate the separator/view
  // flags. Kept here so all flag mutation lives in one class. Callers pass already-formatted
  // strings (Ansi colors applied at the facade); BlockState never knows about colors.

  /** Header at command start (prints `<blank>` + [headerLine], opens a new "view"). */
  fun header(headerLine: String): List<RenderOp> {
    needsSeparator = true
    viewClosed = false
    return listOf(WriteLine(), WriteLine(headerLine))
  }

  /** Bold subtitle printed under [header]. */
  fun subtitle(subtitleLine: String): List<RenderOp> {
    needsSeparator = true
    return listOf(WriteLine(), WriteLine(subtitleLine))
  }

  /** Trailing blank line at command end. Idempotent within a single view (reset by [header]). */
  fun endView(): List<RenderOp> {
    if (viewClosed) return emptyList()
    viewClosed = true
    return listOf(WriteLine())
  }

  /** "Cancelled" banner after a [PromptCancelledException]. */
  fun cancelled(cancelledLine: String): List<RenderOp> {
    needsSeparator = true
    return listOf(WriteLine(), WriteLine(cancelledLine))
  }

  // ── Spinner ────────────────────────────────────────────────────────

  /**
   * Sets the spinner message/substatus and (re)draws the footer. If no block is active, the caller
   * is expected to have opened an auto-block via [beginBlock] first; this method does not change
   * `blockActive` itself.
   */
  fun setSpinner(message: String?, substatus: String?): List<RenderOp> {
    spinnerMessage = message
    spinnerSubstatus = substatus
    spinnerFrameIndex = 0
    return redraw()
  }

  /** Updates the spinner substatus without resetting the frame counter. */
  fun setSpinnerSubstatus(substatus: String): List<RenderOp> {
    if (spinnerMessage == null) {
      // Fall back to a normal emit — caller-facing facade picks the format.
      return emit("  ${Ansi.dim(substatus)}")
    }
    spinnerSubstatus = substatus
    return redraw()
  }

  /** Advances the spinner one frame and redraws. No-op when no spinner is active. */
  fun tickSpinner(): List<RenderOp> {
    if (spinnerMessage == null) return emptyList()
    spinnerFrameIndex = (spinnerFrameIndex + 1) % SPINNER_FRAMES.size
    return redraw()
  }

  /**
   * Clears the spinner state and erases the pinned footer. Does NOT commit block lines or close the
   * block — callers wanting that follow up with [endBlock]. Used by `spin {}`'s finally.
   */
  fun clearSpinner(): List<RenderOp> {
    spinnerMessage = null
    spinnerSubstatus = null
    if (!isTty || displayedLineCount == 0) return emptyList()
    val ops = listOf<RenderOp>(ClearFooter(displayedLineCount))
    displayedLineCount = 0
    return ops
  }

  // ── Internals ──────────────────────────────────────────────────────

  /** Recomputes the pinned-footer ops: clear, optional separator, block lines, optional spinner. */
  private fun redraw(): List<RenderOp> {
    if (!isTty) return emptyList()
    val ops = mutableListOf<RenderOp>()
    if (displayedLineCount > 0) ops += ClearFooter(displayedLineCount)
    val sep = needsSeparator || currentBlockType == BlockType.TRANSIENT || hasLogOutput
    if (sep) ops += WriteLine()
    for (line in blockLines) ops += WriteLine(line)
    val spinnerLine = currentSpinnerLine()
    if (spinnerLine != null) ops += WriteLine(spinnerLine)
    displayedLineCount = (if (sep) 1 else 0) + blockLines.size + (if (spinnerLine != null) 1 else 0)
    return ops
  }

  private fun currentSpinnerLine(): String? {
    val msg = spinnerMessage ?: return null
    val frame = Ansi.cyan(SPINNER_FRAMES[spinnerFrameIndex])
    val sub = spinnerSubstatus
    val detail = if (sub != null) "  ${Ansi.dim(sub)}" else ""
    return "  $frame  ${Ansi.dim(msg)}$detail"
  }

  private fun resetBlock() {
    blockLines.clear()
    spinnerMessage = null
    spinnerSubstatus = null
    displayedLineCount = 0
    blockActive = false
    hasLogOutput = false
  }

  companion object {
    val SPINNER_FRAMES = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
  }
}

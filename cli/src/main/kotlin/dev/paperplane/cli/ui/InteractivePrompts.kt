package dev.paperplane.cli.ui

import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.NonBlockingReader

/**
 * Interactive input primitives: text prompts, arrow-key menus, y/N confirmations.
 *
 * Owns the JLine terminal singleton, raw-mode lifecycle, and the `PromptCancelledException` path
 * for Ctrl+C / ESC / EOF. Separate from [TerminalUI] (which owns block/footer rendering and typed
 * emit methods) because it has no overlap beyond reusing color helpers.
 */
object InteractivePrompts {
  private const val BOTTOM_PADDING = 1
  private const val ARROW_KEY_PEEK_TIMEOUT_MS = 50L

  /** ASCII control codes consumed by the raw-mode keystroke loop. */
  private object AsciiKeys {
    const val CTRL_C = 3
    const val BACKSPACE = 8
    const val LF = 10
    const val CR = 13
    const val ESC = 27
    const val FIRST_PRINTABLE = 32
    const val DEL = 127
  }

  private val isTty = System.console() != null

  // JLine terminal + raw-mode view state
  private val terminalLock = Any()
  private var terminalInstance: Terminal? = null
  private var savedAttributes: Attributes? = null
  private var viewActive = false

  private fun terminal(): Terminal =
      synchronized(terminalLock) {
        terminalInstance
            ?: TerminalBuilder.builder().system(true).dumb(true).build().also {
              terminalInstance = it
            }
      }

  /**
   * Enters raw mode for the duration of an interactive wizard. Safe to call once per command;
   * nested prompts/selects reuse the single raw-mode session instead of toggling termios per
   * prompt.
   */
  fun beginInteractiveView() {
    if (!isTty) return
    synchronized(terminalLock) {
      if (viewActive) return
      val t = terminal()
      savedAttributes = t.enterRawMode()
      // JLine's enterRawMode leaves ISIG enabled, so Ctrl+C still generates SIGINT at the OS
      // level instead of being delivered as byte 3 — the JVM terminates before our
      // PromptCancelledException handler can print the "Cancelled" banner. Clear ISIG so
      // Ctrl+C / Ctrl+\ arrive as raw bytes to the keystroke loop.
      val attrs = Attributes(t.attributes)
      attrs.setLocalFlag(Attributes.LocalFlag.ISIG, false)
      t.attributes = attrs
      viewActive = true
    }
  }

  /** Restores terminal attributes saved by [beginInteractiveView]. Idempotent. */
  fun endInteractiveView() {
    synchronized(terminalLock) {
      val saved = savedAttributes ?: return
      try {
        terminalInstance?.attributes = saved
      } catch (_: Exception) {
        // best-effort
      }
      savedAttributes = null
      viewActive = false
    }
  }

  /**
   * Safety-net for shutdown hooks: forces the terminal back to its pre-wizard state if an
   * interactive view was ever opened. No-op otherwise.
   */
  fun restoreTerminalIfNeeded() {
    endInteractiveView()
  }

  /**
   * Runs [block] with the terminal in raw mode. If an interactive view is active, reuses its
   * raw-mode state. Otherwise saves + restores attributes around [block].
   */
  private inline fun <T> withRawTty(block: (reader: NonBlockingReader) -> T): T {
    val t = terminal()
    val ownsRawMode: Boolean
    val localSaved: Attributes?
    synchronized(terminalLock) {
      if (viewActive) {
        ownsRawMode = false
        localSaved = null
      } else {
        ownsRawMode = true
        localSaved = t.enterRawMode()
        // See beginInteractiveView — clear ISIG so Ctrl+C arrives as a byte, not a signal.
        val attrs = Attributes(t.attributes)
        attrs.setLocalFlag(Attributes.LocalFlag.ISIG, false)
        t.attributes = attrs
      }
    }
    try {
      return block(t.reader())
    } finally {
      if (ownsRawMode && localSaved != null) {
        try {
          t.attributes = localSaved
        } catch (_: Exception) {
          // best-effort
        }
      }
    }
  }

  /** Throws [PromptCancelledException]. Collapses the per-keystroke cancellation sites. */
  private fun cancelPrompt(): Nothing = throw PromptCancelledException()

  // ── Prompt (text input) ────────────────────────────────────────────

  /**
   * Prompts the user for text input with an optional default (Vite-style). Shows a two-line active
   * state with `›` prefix and dim placeholder, collapsing to a `◇` completed state after input. In
   * TTY mode, uses raw input for placeholder replacement. Falls back to simple line input
   * otherwise.
   */
  fun prompt(label: String, default: String? = null): String {
    if (!isTty) return promptFallback(label, default)
    while (true) {
      renderPromptActive(label, default)
      val entered = withRawTty { reader -> readPromptLine(default, reader) }
      if (entered == null) continue // empty input + no default → re-render and loop
      renderPromptCommitted(label, entered)
      return entered
    }
  }

  private fun renderPromptActive(label: String, default: String?) {
    Writer.writeLine()
    Writer.writeLine("  ${Ansi.cyan("›")}  $label:")
    val placeholder = default ?: ""
    Writer.write("     ${Ansi.dim(placeholder)}")
    // Add bottom padding, then move cursor back to input line
    Writer.write("\n".repeat(BOTTOM_PADDING) + "\u001b[${BOTTOM_PADDING}A\r\u001b[5C")
    Writer.flush()
  }

  private fun renderPromptCommitted(label: String, result: String) {
    // Collapse to completed state: move up to label line, rewrite label + input
    Writer.write("\r\u001b[1A\u001b[2K\r")
    Writer.writeLine("  ${Ansi.dim("◇")}  $label:")
    Writer.write("\u001b[2K")
    Writer.writeLine("     $result")
    // Clear bottom padding lines
    for (i in 0 until BOTTOM_PADDING) {
      Writer.write("\u001b[2K\n")
    }
    Writer.write("\u001b[${BOTTOM_PADDING}A")
    Writer.flush()
  }

  /**
   * Reads keystrokes until Enter with non-empty content (→ returns String), Enter with empty
   * content and no default (→ returns null for caller to loop), or Ctrl+C / ESC / EOF (→ throws
   * [PromptCancelledException]).
   *
   * Visible for testing — exposes the input-loop state machine without requiring a real terminal.
   *
   * Suppressions: this is a keystroke state machine where each branch handles a distinct key (EOF,
   * Ctrl+C, ESC, Enter, backspace, arrow-key escape sequence, printable). Decomposing further would
   * split tightly-related branches across unrelated helpers and obscure intent.
   */
  @Suppress("CyclomaticComplexMethod", "LongMethod")
  internal fun readPromptLine(default: String?, reader: NonBlockingReader): String? {
    val input = StringBuilder()
    var usingDefault = default != null
    while (true) {
      val b = reader.read()
      when (b) {
        -1, // EOF
        AsciiKeys.CTRL_C -> {
          Writer.writeLine()
          cancelPrompt()
        }
        AsciiKeys.ESC -> {
          // Arrow keys send ESC [ A/B/C/D; swallow the sequence and continue.
          // A bare ESC (no follow-up within the peek timeout) cancels the prompt.
          val next = reader.peek(ARROW_KEY_PEEK_TIMEOUT_MS)
          if (next >= 0 && reader.read() == '['.code) {
            reader.read() // consume direction byte (A/B/C/D) and ignore
          } else {
            Writer.writeLine()
            cancelPrompt()
          }
        }
        AsciiKeys.CR,
        AsciiKeys.LF -> { // Enter
          val result = if (usingDefault && input.isEmpty()) default ?: "" else input.toString()
          if (result.isEmpty() && default == null) {
            // Re-render input line; caller re-enters via outer loop
            Writer.write("\r\u001b[2K     ")
            Writer.flush()
            return null
          }
          return result
        }
        AsciiKeys.DEL,
        AsciiKeys.BACKSPACE -> {
          if (usingDefault) {
            input.clear()
            usingDefault = false
          } else if (input.isNotEmpty()) {
            input.deleteCharAt(input.length - 1)
          }
          if (input.isEmpty() && !usingDefault && default != null) {
            // Restore placeholder when input is cleared
            usingDefault = true
            Writer.write("\r\u001b[2K     ${Ansi.dim(default)}\r\u001b[5C")
          } else {
            Writer.write("\r\u001b[2K     ${input}")
          }
          Writer.flush()
        }
        else -> {
          if (b >= AsciiKeys.FIRST_PRINTABLE && !Character.isISOControl(b)) {
            if (usingDefault) {
              input.clear()
              usingDefault = false
            }
            input.append(b.toChar())
            Writer.write("\r\u001b[2K     ${input}")
            Writer.flush()
          }
        }
      }
    }
    @Suppress("UNREACHABLE_CODE")
    return null
  }

  /** Fallback prompt for non-TTY environments. */
  private fun promptFallback(label: String, default: String?): String {
    while (true) {
      val suffix = if (default != null) " ${Ansi.dim("($default)")}" else ""
      Writer.write("  $label$suffix: ")
      Writer.flush()
      val input = readlnOrNull()?.trim()
      if (!input.isNullOrEmpty()) return input
      if (default != null) return default
    }
  }

  // ── Select (arrow-key menu) ────────────────────────────────────────

  /** A single option in a [select] menu. */
  data class SelectOption(val label: String, val description: String? = null)

  /**
   * Convenience overload taking plain string labels. Returns the selected index. On non-TTY
   * terminals, falls back to a numbered list with line input.
   */
  @JvmName("selectStrings")
  fun select(label: String, options: List<String>, note: String? = null, default: Int = 0): Int =
      select(label, options.map { SelectOption(it) }, note, default)

  /**
   * Displays an arrow-key selection menu. Must be called outside any block with no spinner active.
   * Returns the selected index.
   */
  fun select(
      label: String,
      options: List<SelectOption>,
      note: String? = null,
      default: Int = 0,
  ): Int {
    if (!isTty) return selectFallback(label, options, default)
    renderSelectHeader(label, note)
    Writer.write("\u001b[?25l") // hide cursor
    renderSelectOptions(options, default)
    Writer.write("\n".repeat(BOTTOM_PADDING) + "\u001b[${BOTTOM_PADDING}A")
    Writer.flush()
    val selected =
        try {
          runSelectInputLoop(options, default)
        } finally {
          Writer.write("\u001b[?25h")
          Writer.flush()
        }
    renderSelectCommitted(label, options, selected)
    return selected
  }

  private fun renderSelectHeader(label: String, note: String?) {
    val noteText = if (note != null) "  ${Ansi.dim(note)}" else ""
    Writer.writeLine()
    Writer.writeLine("  ${Ansi.cyan("›")}  ${Ansi.bold(Ansi.brightWhite(label))}:$noteText")
  }

  private fun runSelectInputLoop(options: List<SelectOption>, initial: Int): Int {
    var selected = initial
    withRawTty { reader ->
      loop@ while (true) {
        when (reader.read()) {
          -1,
          AsciiKeys.CTRL_C -> {
            Writer.write("\u001b[?25h")
            Writer.writeLine()
            cancelPrompt()
          }
          AsciiKeys.CR,
          AsciiKeys.LF -> break@loop // Enter
          AsciiKeys.ESC -> { // Escape or escape sequence
            // Arrow keys send ESC [ A/B; peek briefly for the follow-up bytes.
            val next = reader.peek(ARROW_KEY_PEEK_TIMEOUT_MS)
            if (next >= 0 && reader.read() == '['.code) {
              when (reader.read()) {
                'A'.code -> selected = (selected - 1 + options.size) % options.size
                'B'.code -> selected = (selected + 1) % options.size
              }
              Writer.write("\u001b[${options.size}A")
              renderSelectOptions(options, selected)
              Writer.flush()
            } else {
              Writer.write("\u001b[?25h")
              Writer.writeLine()
              cancelPrompt()
            }
          }
        }
      }
    }
    return selected
  }

  private fun renderSelectCommitted(label: String, options: List<SelectOption>, selected: Int) {
    val totalLines = options.size + 1
    Writer.write("\u001b[${totalLines}A")
    for (i in 0 until totalLines) {
      Writer.write("\u001b[2K")
      if (i < totalLines - 1) Writer.write("\u001b[1B")
    }
    if (totalLines > 1) Writer.write("\u001b[${totalLines - 1}A")
    Writer.write("\r")

    Writer.writeLine("  ${Ansi.dim("◇")}  $label:")
    Writer.writeLine("     ${options[selected].label}")

    val excess = totalLines - 2 + BOTTOM_PADDING
    for (i in 0 until excess) {
      Writer.write("\u001b[2K\n")
    }
    if (excess > 0) Writer.write("\u001b[${excess}A")
    Writer.flush()
  }

  /**
   * Renders option lines into a single buffered `print`, clearing each line first. Cursor ends
   * after the last line. Buffering avoids 2-3 syscalls per option on every arrow-key redraw.
   */
  private fun renderSelectOptions(options: List<SelectOption>, selected: Int) {
    val sb = StringBuilder()
    for ((i, option) in options.withIndex()) {
      sb.append("\u001b[2K\r")
      val marker = if (i == selected) "${Ansi.cyan("›")} " else "  "
      val descText = option.description?.let { " ${Ansi.dim("— $it")}" } ?: ""
      val text = if (i == selected) Ansi.brightWhite(option.label) else Ansi.dim(option.label)
      sb.append("    ").append(marker).append(text).append(descText)
      sb.append('\n')
    }
    Writer.write(sb.toString())
  }

  /** Fallback selection for non-TTY environments: numbered list with line input. */
  private fun selectFallback(label: String, options: List<SelectOption>, default: Int): Int {
    Writer.writeLine()
    Writer.writeLine("  $label")
    for ((i, option) in options.withIndex()) {
      val descText = option.description?.let { " — $it" } ?: ""
      val marker = if (i == default) " (default)" else ""
      Writer.writeLine("    ${i + 1}. ${option.label}$descText$marker")
    }
    Writer.write("  Choice [${default + 1}]: ")
    Writer.flush()
    val input = readlnOrNull()?.trim()
    if (input.isNullOrEmpty()) return default
    return (input.toIntOrNull()?.minus(1))?.coerceIn(0, options.size - 1) ?: default
  }

  // ── Confirm (y/N) ──────────────────────────────────────────────────

  /** Prints a confirmation prompt and returns true if the user answers y/yes. */
  fun confirm(message: String): Boolean {
    Writer.writeLine()
    Writer.write("  $message (y/N): ")
    val answer = readlnOrNull()?.trim()?.lowercase()
    return answer == "y" || answer == "yes"
  }
}

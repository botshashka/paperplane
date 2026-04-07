package dev.paperplane.cli.ui

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object TerminalUI {
  private const val SPINNER_FRAME_INTERVAL_MS = 80L
  private const val SPINNER_THREAD_JOIN_TIMEOUT_MS = 200L
  private const val BOTTOM_PADDING = 1

  private val noColor = System.getenv("NO_COLOR") != null
  private val isTty = System.console() != null
  val useColor: Boolean = !noColor

  // ANSI codes
  private const val RESET = "\u001b[0m"
  private const val BOLD = "\u001b[1m"
  private const val DIM = "\u001b[2m"
  private const val RED = "\u001b[31m"
  private const val GREEN = "\u001b[32m"
  private const val YELLOW = "\u001b[33m"
  private const val BLUE = "\u001b[34m"
  private const val CYAN = "\u001b[36m"
  private const val WHITE = "\u001b[37m"
  private const val BRIGHT_WHITE = "\u001b[97m"

  enum class BlockType {
    PERSIST, // printed to scroll when ended (startup, rebuild, info blocks)
    TRANSIENT, // erased silently when ended (watching/waiting status)
  }

  // Sticky footer state — all guarded by [lock]
  private val lock = ReentrantLock()
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

  private val spinnerFrames = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

  // ── Color helpers ──────────────────────────────────────────────────

  internal fun color(code: String, text: String): String = if (noColor) text else "$code$text$RESET"

  internal fun bold(text: String) = color(BOLD, text)

  internal fun dim(text: String) = color(DIM, text)

  internal fun green(text: String) = color(GREEN, text)

  internal fun red(text: String) = color(RED, text)

  internal fun yellow(text: String) = color(YELLOW, text)

  internal fun cyan(text: String) = color(CYAN, text)

  internal fun brightWhite(text: String) = color(BRIGHT_WHITE, text)

  // ── Sticky footer internals ────────────────────────────────────────

  /** Erases the currently displayed footer from the terminal. Must hold [lock]. */
  private fun clearDisplay() {
    if (displayedLineCount <= 0) return
    print("\u001b[${displayedLineCount}A")
    repeat(displayedLineCount) { print("\u001b[2K\n") }
    print("\u001b[${displayedLineCount}A")
    System.out.flush()
    displayedLineCount = 0
  }

  /** Redraws the full footer (block lines + optional spinner). Must hold [lock]. */
  private fun redraw() {
    if (!isTty) return
    clearDisplay()
    // Blank separator only after server/proxy logs have appeared above
    val sep =
        if (needsSeparator || currentBlockType == BlockType.TRANSIENT || hasLogOutput) {
          println()
          1
        } else 0
    for (line in blockLines) {
      println(line)
    }
    val extra =
        if (spinnerMessage != null) {
          val frame =
              if (noColor) spinnerFrames[spinnerFrameIndex]
              else "$CYAN${spinnerFrames[spinnerFrameIndex]}$RESET"
          val sub = spinnerSubstatus
          val detail = if (sub != null) "  ${dim(sub)}" else ""
          println("  $frame  ${dim(spinnerMessage!!)}$detail")
          1
        } else 0
    displayedLineCount = sep + blockLines.size + extra
  }

  /** If needed, prints a blank separator line before scroll-committed output. Must hold [lock]. */
  private fun emitSeparatorIfNeeded() {
    if (needsSeparator) {
      println()
      needsSeparator = false
    }
  }

  private fun resetBlock() {
    blockLines.clear()
    spinnerMessage = null
    spinnerSubstatus = null
    displayedLineCount = 0
    blockActive = false
    hasLogOutput = false
  }

  // ── Block lifecycle ────────────────────────────────────────────────

  /**
   * Starts a new pinned footer block. [type] determines what happens when the block ends:
   * - [BlockType.PERSIST]: content is printed to scroll
   * - [BlockType.TRANSIENT]: content is silently erased
   */
  fun beginBlock(type: BlockType = BlockType.PERSIST) {
    lock.withLock {
      blockActive = true
      currentBlockType = type
      hasLogOutput = false
    }
  }

  /**
   * Ends the current block. PERSIST blocks are printed to scroll with a blank line before (not
   * after) for separation from previous content. TRANSIENT blocks are silently erased.
   */
  fun endBlock() {
    lock.withLock {
      if (!blockActive && blockLines.isEmpty()) return
      if (isTty) clearDisplay()
      if (currentBlockType == BlockType.PERSIST && blockLines.isNotEmpty()) {
        emitSeparatorIfNeeded()
        for (line in blockLines) {
          println(line)
        }
        needsSeparator = true
      }
      resetBlock()
    }
  }

  /**
   * Discards the current pinned block without printing it, regardless of type. Use in shutdown
   * hooks where no new block follows.
   */
  fun discardBlock() {
    lock.withLock {
      if (!blockActive && blockLines.isEmpty()) return
      if (isTty) clearDisplay()
      resetBlock()
    }
  }

  /**
   * Ends the current block and starts a transient "Watching/Waiting" block. Convenience for the
   * pattern that repeats throughout DevCommand.
   */
  fun awaitChanges(watching: Boolean = true) {
    endBlock()
    beginBlock(BlockType.TRANSIENT)
    status(if (watching) "Watching for changes..." else "Waiting for changes...")
  }

  // ── Output methods (block-aware) ───────────────────────────────────

  private fun emit(text: String) {
    lock.withLock {
      if (blockActive && isTty) {
        blockLines.add(text)
        redraw()
      } else {
        println(text)
      }
    }
  }

  /**
   * Prints a server/proxy log line above the pinned footer. When no block is active, prints
   * directly.
   */
  fun serverLog(line: String) {
    lock.withLock {
      if (blockActive && isTty && displayedLineCount > 0) {
        val first = !hasLogOutput
        hasLogOutput = true
        clearDisplay()
        if (first) emitSeparatorIfNeeded()
        println(line)
        needsSeparator = true
        redraw()
      } else {
        if (!hasLogOutput) emitSeparatorIfNeeded()
        hasLogOutput = true
        println(line)
        needsSeparator = true
      }
    }
  }

  // ── Public status methods ──────────────────────────────────────────

  fun header(version: String) {
    println()
    println("  ${cyan("✈")}  ${bold(cyan("PaperPlane"))} ${dim("v$version")}")
    needsSeparator = true
    viewClosed = false
  }

  /**
   * Bold subtitle line printed directly under [header]. Like [header], it bypasses the block system
   * since it appears before any block activity. Preserves a blank line above for separation from
   * the header.
   */
  fun subtitle(text: String) {
    println()
    println("  ${bold(brightWhite(text))}")
    needsSeparator = true
  }

  /**
   * Marks the end of a "view" (a full command's output). Emits one trailing blank line so the shell
   * prompt has breathing room. Idempotent — safe to call multiple times within a single command.
   * Reset by [header]. Every command should call this at the end of its `run()`.
   */
  fun endView() {
    lock.withLock {
      if (viewClosed) return
      viewClosed = true
      println()
    }
  }

  fun success(message: String, duration: String? = null) {
    val text =
        if (duration != null) {
          "  ${green("✓")}  $message ${dim(duration)}"
        } else {
          "  ${green("✓")}  $message"
        }
    emit(text)
  }

  fun error(message: String, duration: String? = null) {
    val text =
        if (duration != null) {
          "  ${red("✗")}  $message ${dim(duration)}"
        } else {
          "  ${red("✗")}  $message"
        }
    emit(text)
  }

  fun info(label: String, value: String) {
    emit("  ${dim("➜")}  ${bold(brightWhite(label))}  $value")
  }

  fun status(message: String) {
    emit("  ${dim(message)}")
  }

  fun change(message: String) {
    emit("  ${yellow("⟳")}  $message")
  }

  fun totalTime(duration: String) {
    emit("  ${dim("total $duration")}")
  }

  fun buildError(filePath: String, line: Int?, errorText: String) {
    val location = if (line != null) "$filePath:$line" else filePath
    emit("  ${brightWhite(location)}")
    for (errLine in errorText.lines()) {
      emit("  ${red(errLine)}")
    }
  }

  fun testFailure(errorText: String) {
    val lines = errorText.lines().filter { it.isNotBlank() }
    for (errLine in lines) {
      emit("       ${red(errLine)}")
    }
  }

  fun blank() {
    emit("")
  }

  /** Prints a confirmation prompt and returns true if the user answers y/yes. */
  fun confirm(message: String): Boolean {
    println()
    print("  $message (y/N): ")
    val answer = readlnOrNull()?.trim()?.lowercase()
    return answer == "y" || answer == "yes"
  }

  /**
   * Prompts the user for text input with an optional default (Vite-style). Shows a two-line active
   * state with `›` prefix and dim placeholder, collapsing to a `◇` completed state after input.
   *
   * In TTY mode, uses raw input for placeholder replacement. Falls back to simple line input
   * otherwise.
   */
  fun prompt(label: String, default: String? = null): String {
    if (!isTty) return promptFallback(label, default)

    while (true) {
      // Active state: label + placeholder
      println()
      println("  ${cyan("›")}  $label:")
      val placeholder = default ?: ""
      print("     ${dim(placeholder)}")
      // Add bottom padding, then move cursor back to input line
      print("\n".repeat(BOTTOM_PADDING) + "\u001b[${BOTTOM_PADDING}A\r\u001b[5C")
      System.out.flush()

      val input = StringBuilder()
      var usingDefault = default != null
      val savedStty = sttyCapture("-g")

      try {
        sttySet("raw", "-echo")

        while (true) {
          val b = System.`in`.read()
          when (b) {
            3 -> { // Ctrl+C
              restoreStty(savedStty)
              println()
              System.exit(130)
            }
            27 -> { // ESC — abort
              restoreStty(savedStty)
              println()
              System.exit(130)
            }
            13,
            10 -> { // Enter
              val result = if (usingDefault && input.isEmpty()) default ?: "" else input.toString()
              if (result.isEmpty() && default == null) {
                // Re-render input line (stay in loop)
                print("\r\u001b[2K     ")
                System.out.flush()
                continue
              }

              restoreStty(savedStty)
              // Collapse to completed state: move up to label line, rewrite label + input
              print("\r\u001b[1A\u001b[2K\r")
              println("  ${dim("◇")}  $label:")
              print("\u001b[2K")
              println("     $result")
              // Clear bottom padding lines
              for (i in 0 until BOTTOM_PADDING) {
                print("\u001b[2K\n")
              }
              print("\u001b[${BOTTOM_PADDING}A")
              System.out.flush()
              return result
            }
            127,
            8 -> { // Backspace
              if (usingDefault) {
                input.clear()
                usingDefault = false
              } else if (input.isNotEmpty()) {
                input.deleteCharAt(input.length - 1)
              }
              if (input.isEmpty() && !usingDefault && default != null) {
                // Restore placeholder when input is cleared
                usingDefault = true
                print("\r\u001b[2K     ${dim(default)}\r\u001b[5C")
              } else {
                print("\r\u001b[2K     ${input}")
              }
              System.out.flush()
            }
            else -> {
              if (b in 32..126) {
                if (usingDefault) {
                  input.clear()
                  usingDefault = false
                }
                input.append(b.toChar())
                print("\r\u001b[2K     ${input}")
                System.out.flush()
              }
            }
          }
        }
      } catch (e: Exception) {
        restoreStty(savedStty)
        throw e
      }
    }
  }

  /** Fallback prompt for non-TTY environments. */
  private fun promptFallback(label: String, default: String?): String {
    while (true) {
      val suffix = if (default != null) " ${dim("($default)")}" else ""
      print("  $label$suffix: ")
      System.out.flush()
      val input = readlnOrNull()?.trim()
      if (!input.isNullOrEmpty()) return input
      if (default != null) return default
    }
  }

  /** A single option in a [select] menu. */
  data class SelectOption(val label: String, val description: String? = null)

  /**
   * Convenience overload taking plain string labels. Returns the selected index.
   *
   * On non-TTY terminals, falls back to a numbered list with line input.
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

    val noteText = if (note != null) "  ${dim(note)}" else ""
    println()
    println("  ${cyan("›")}  ${bold(brightWhite(label))}:$noteText")

    var selected = default

    print("\u001b[?25l") // hide cursor
    renderSelectOptions(options, selected)
    print("\n".repeat(BOTTOM_PADDING) + "\u001b[${BOTTOM_PADDING}A")
    System.out.flush()

    val savedStty = sttyCapture("-g")

    try {
      sttySet("raw", "-echo")

      while (true) {
        val b = System.`in`.read()
        when (b) {
          3 -> { // Ctrl+C
            restoreStty(savedStty)
            println()
            System.exit(130)
            break
          }
          13,
          10 -> break // Enter
          27 -> { // Escape or escape sequence
            // Arrow keys send ESC [ A/B; wait briefly for the follow-up bytes.
            Thread.sleep(20)
            if (System.`in`.available() > 0 && System.`in`.read() == '['.code) {
              when (System.`in`.read()) {
                'A'.code -> selected = (selected - 1 + options.size) % options.size
                'B'.code -> selected = (selected + 1) % options.size
              }
              print("\u001b[${options.size}A")
              renderSelectOptions(options, selected)
              System.out.flush()
            } else {
              restoreStty(savedStty)
              print("\u001b[?25h")
              println()
              System.exit(130)
            }
          }
        }
      }
    } finally {
      restoreStty(savedStty)
      print("\u001b[?25h")
      System.out.flush()
    }

    val totalLines = options.size + 1
    print("\u001b[${totalLines}A")
    for (i in 0 until totalLines) {
      print("\u001b[2K")
      if (i < totalLines - 1) print("\u001b[1B")
    }
    if (totalLines > 1) print("\u001b[${totalLines - 1}A")
    print("\r")

    println("  ${dim("◇")}  $label:")
    println("     ${options[selected].label}")

    val excess = totalLines - 2 + BOTTOM_PADDING
    for (i in 0 until excess) {
      print("\u001b[2K\n")
    }
    if (excess > 0) print("\u001b[${excess}A")
    System.out.flush()

    return selected
  }

  /**
   * Renders option lines into a single buffered `print`, clearing each line first. Cursor ends
   * after the last line. Buffering avoids 2-3 syscalls per option on every arrow-key redraw.
   */
  private fun renderSelectOptions(options: List<SelectOption>, selected: Int) {
    val sb = StringBuilder()
    for ((i, option) in options.withIndex()) {
      sb.append("\u001b[2K\r")
      val marker = if (i == selected) "${cyan("›")} " else "  "
      val descText = option.description?.let { " ${dim("— $it")}" } ?: ""
      val text = if (i == selected) brightWhite(option.label) else dim(option.label)
      sb.append("    ").append(marker).append(text).append(descText)
      sb.append('\n')
    }
    print(sb.toString())
  }

  /** Fallback selection for non-TTY environments: numbered list with line input. */
  private fun selectFallback(label: String, options: List<SelectOption>, default: Int): Int {
    println()
    println("  $label")
    for ((i, option) in options.withIndex()) {
      val descText = option.description?.let { " — $it" } ?: ""
      val marker = if (i == default) " (default)" else ""
      println("    ${i + 1}. ${option.label}$descText$marker")
    }
    print("  Choice [${default + 1}]: ")
    System.out.flush()
    val input = readlnOrNull()?.trim()
    if (input.isNullOrEmpty()) return default
    return (input.toIntOrNull()?.minus(1))?.coerceIn(0, options.size - 1) ?: default
  }

  private fun sttyCapture(vararg args: String): String {
    val proc = ProcessBuilder("stty", *args).redirectInput(ProcessBuilder.Redirect.INHERIT).start()
    val result = proc.inputStream.bufferedReader().readText().trim()
    proc.waitFor()
    return result
  }

  private fun sttySet(vararg args: String) {
    ProcessBuilder("stty", *args).redirectInput(ProcessBuilder.Redirect.INHERIT).start().waitFor()
  }

  private fun restoreStty(saved: String) {
    try {
      sttySet(saved)
    } catch (_: Exception) {
      // Best effort restore
    }
  }

  fun fileCreated(path: String) {
    emit("  ${green("✓")} $path")
  }

  // Test output (Vitest-style)
  fun testClass(name: String, passed: Boolean, duration: String) {
    val icon = if (passed) green("✓") else red("✗")
    emit("  $icon  $name ${dim(duration)}")
  }

  fun testCase(name: String, passed: Boolean, duration: String) {
    val icon = if (passed) green("✓") else red("✗")
    emit("     $icon $name ${dim(duration)}")
  }

  fun testSummary(passed: Int, failed: Int, total: Int, buildTime: String, testTime: String) {
    emit("")
    val passedText = green("$passed passed")
    val failedText = if (failed > 0) "  ${red("$failed failed")}" else ""
    emit("  Tests   $passedText$failedText  ${dim("($total)")}")
    emit("  Time    ${buildTime} ${dim("(build ${buildTime}, tests ${testTime})")}")
  }

  // ── Spinner ────────────────────────────────────────────────────────

  /**
   * Updates the detail text shown after the spinner message on the same line. Only meaningful while
   * a [spin] block is executing; otherwise falls back to [status].
   */
  fun spinSubstatus(text: String) {
    lock.withLock {
      if (spinnerMessage != null) {
        spinnerSubstatus = text
        redraw()
      } else {
        emit("  ${dim(text)}")
      }
    }
  }

  /**
   * Runs [block] while showing a spinner with [message] pinned in the footer. Server/proxy logs
   * scroll above; the spinner stays at the bottom. Code inside [block] can call [spinSubstatus] to
   * update detail text.
   */
  fun <T> spin(message: String, block: () -> T): T {
    val autoBlock = lock.withLock {
      val auto = !blockActive
      if (auto) {
        blockActive = true
        currentBlockType = BlockType.PERSIST
      }
      spinnerMessage = message
      spinnerSubstatus = null
      spinnerFrameIndex = 0
      redraw()
      auto
    }

    val done = AtomicBoolean(false)
    val thread =
        Thread(
            {
              var i = 0
              while (!done.get()) {
                Thread.sleep(SPINNER_FRAME_INTERVAL_MS)
                lock.withLock {
                  if (!done.get()) {
                    i = (i + 1) % spinnerFrames.size
                    spinnerFrameIndex = i
                    redraw()
                  }
                }
              }
            },
            "spinner",
        )
    thread.isDaemon = true
    thread.start()

    return try {
      block()
    } finally {
      done.set(true)
      thread.join(SPINNER_THREAD_JOIN_TIMEOUT_MS)
      lock.withLock {
        spinnerMessage = null
        spinnerSubstatus = null
        if (isTty) {
          clearDisplay()
        }
        if (autoBlock) {
          if (blockLines.isNotEmpty()) {
            emitSeparatorIfNeeded()
            for (line in blockLines) {
              println(line)
            }
            needsSeparator = true
          }
          resetBlock()
        }
      }
    }
  }
}

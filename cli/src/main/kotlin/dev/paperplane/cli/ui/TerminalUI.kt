package dev.paperplane.cli.ui

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object TerminalUI {
  private const val SPINNER_FRAME_INTERVAL_MS = 80L
  private const val SPINNER_THREAD_JOIN_TIMEOUT_MS = 200L

  private val noColor = System.getenv("NO_COLOR") != null
  private val isTty = System.console() != null

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

  private val spinnerFrames = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

  // ── Color helpers ──────────────────────────────────────────────────

  private fun color(code: String, text: String): String = if (noColor) text else "$code$text$RESET"

  private fun bold(text: String) = color(BOLD, text)

  private fun dim(text: String) = color(DIM, text)

  private fun green(text: String) = color(GREEN, text)

  private fun red(text: String) = color(RED, text)

  private fun yellow(text: String) = color(YELLOW, text)

  private fun cyan(text: String) = color(CYAN, text)

  private fun brightWhite(text: String) = color(BRIGHT_WHITE, text)

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
        if (isTty) redraw()
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

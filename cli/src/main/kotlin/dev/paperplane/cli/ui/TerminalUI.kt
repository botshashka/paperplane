package dev.paperplane.cli.ui

import dev.paperplane.cli.ui.Ansi.bold
import dev.paperplane.cli.ui.Ansi.brightWhite
import dev.paperplane.cli.ui.Ansi.cyan
import dev.paperplane.cli.ui.Ansi.dim
import dev.paperplane.cli.ui.Ansi.green
import dev.paperplane.cli.ui.Ansi.red
import dev.paperplane.cli.ui.Ansi.yellow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object TerminalUI {
  private const val SPINNER_FRAME_INTERVAL_MS = 80L
  private const val SPINNER_THREAD_JOIN_TIMEOUT_MS = 200L

  private val isTty = System.console() != null
  /** Backwards-compatible accessor — delegates to [Ansi.useColor]. */
  val useColor: Boolean
    get() = Ansi.useColor

  @PublishedApi
  internal enum class BlockType {
    PERSIST, // printed to scroll when ended (startup, rebuild, info blocks)
    TRANSIENT, // erased silently when ended (watching/waiting status)
  }

  /**
   * Controls what trailing footer [phase] opens after its body returns.
   * - [Watching]: "Watching for changes..." transient footer (normal success flow)
   * - [Waiting]: "Waiting for changes..." transient footer (build/server failure flow)
   * - [None]: no trailing footer (terminal states — shutdown, unrecoverable error)
   */
  enum class PhaseEnd {
    Watching,
    Waiting,
    None,
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

  /**
   * Prints a "Cancelled" banner after a [PromptCancelledException] bubbles up. By the time this
   * runs, `spin()`'s finally has already cleared any pinned footer, so we just need to emit the
   * banner as direct output.
   */
  fun cancelled() {
    lock.withLock {
      println()
      println("  ${yellow("⚠")}  ${dim("Cancelled")}")
      needsSeparator = true
    }
  }

  private val spinnerFrames = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

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
          val frame = cyan(spinnerFrames[spinnerFrameIndex])
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

  // ── Block lifecycle (internal — invoked by block { } / phase { }) ──

  @PublishedApi
  internal fun beginBlock(type: BlockType = BlockType.PERSIST) {
    lock.withLock {
      blockActive = true
      currentBlockType = type
      hasLogOutput = false
    }
  }

  @PublishedApi
  internal fun endBlock() {
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

  @PublishedApi
  internal fun discardBlock() {
    lock.withLock {
      if (!blockActive && blockLines.isEmpty()) return
      if (isTty) clearDisplay()
      resetBlock()
    }
  }

  /**
   * Scoped PERSIST block. Use for command one-shot output:
   * ```
   * TerminalUI.block {
   *     success("Done")
   *     info("files", "12 changed")
   * }
   * ```
   *
   * The lambda receiver is `TerminalUI`, so emit calls are unqualified. The block closes on scope
   * exit even if [body] throws — "forgot to endBlock" becomes impossible.
   */
  inline fun <T> block(body: TerminalUI.() -> T): T {
    beginBlock(BlockType.PERSIST)
    try {
      return body()
    } finally {
      endBlock()
    }
  }

  /**
   * Scoped iteration block for dev-server loops. Discards any prior pinned footer, opens a PERSIST
   * block, runs [body], closes it, then opens a trailing TRANSIENT footer whose label is determined
   * by [body]'s return value.
   *
   * ```
   * TerminalUI.phase {
   *     change("Change detected: Main.java")
   *     if (!build()) return@phase PhaseEnd.Waiting
   *     success("Built")
   *     PhaseEnd.Watching
   * }
   * ```
   *
   * Exceptions inside [body] clear the pinned footer and rethrow — the block doesn't leak.
   */
  inline fun phase(body: TerminalUI.() -> PhaseEnd) {
    discardBlock()
    beginBlock(BlockType.PERSIST)
    var completed = false
    val end: PhaseEnd
    try {
      end = body()
      completed = true
    } finally {
      // body threw → discard the partial block before the throw propagates through finally
      if (!completed) discardBlock()
    }
    endBlock()
    when (end) {
      PhaseEnd.Watching -> {
        beginBlock(BlockType.TRANSIENT)
        status("Watching for changes...")
      }
      PhaseEnd.Waiting -> {
        beginBlock(BlockType.TRANSIENT)
        status("Waiting for changes...")
      }
      PhaseEnd.None -> Unit
    }
  }

  /**
   * Clears any pinned footer. Safety net for shutdown hooks; also used between dev-server phases
   * when a health-check fails. Idempotent — no-op if nothing is pinned.
   */
  fun clearPinnedFooter() {
    discardBlock()
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

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

/**
 * Public CLI rendering facade. Owns concurrency (a [ReentrantLock]), formatting (Ansi colors), and
 * the spinner thread; delegates all state-machine and I/O work to [BlockState] + [BlockRenderer].
 *
 * One instance per CLI process. Constructed in `PaperPlane.main` with an [AnsiTerminal] and
 * threaded through every command, dev-server mode, and helper via constructor injection. Tests
 * construct a `TerminalUI(RecordingTerminal())` and assert on the recorded writes.
 *
 * The block/footer rules — separator handling, PERSIST vs TRANSIENT semantics, log interleaving
 * above the pinned footer, spinner frame management — all live in [BlockState] and are
 * unit-testable without a real terminal.
 */
class TerminalUI(terminal: Terminal) {
  companion object {
    private const val SPINNER_FRAME_INTERVAL_MS = 80L
    private const val SPINNER_THREAD_JOIN_TIMEOUT_MS = 200L
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

  /** Backwards-compatible accessor — delegates to [Ansi.useColor]. */
  val useColor: Boolean
    get() = Ansi.useColor

  private val lock = ReentrantLock()
  private val state = BlockState(terminal.isTty, widthProvider = { terminal.width })
  private val renderer = BlockRenderer(Writer(terminal))

  /** Drives [state] under the lock and renders the resulting ops. */
  private inline fun run(block: BlockState.() -> List<RenderOp>) {
    lock.withLock { renderer.render(state.block()) }
  }

  // ── Block lifecycle (internal — invoked by block { } / phase { }) ──

  @PublishedApi
  internal fun beginBlock(persist: Boolean = true) {
    val type = if (persist) BlockState.BlockType.PERSIST else BlockState.BlockType.TRANSIENT
    run { beginBlock(type) }
  }

  @PublishedApi internal fun endBlock() = run { endBlock() }

  @PublishedApi internal fun discardBlock() = run { discardBlock() }

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
    beginBlock(persist = true)
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
   * Exceptions inside [body] clear the pinned footer and rethrow — the block doesn't leak.
   */
  inline fun phase(body: TerminalUI.() -> PhaseEnd) {
    discardBlock()
    beginBlock(persist = true)
    var completed = false
    val end: PhaseEnd
    try {
      end = body()
      completed = true
    } finally {
      if (!completed) discardBlock()
    }
    endBlock()
    when (end) {
      PhaseEnd.Watching -> {
        beginBlock(persist = false)
        status("Watching for changes...")
      }
      PhaseEnd.Waiting -> {
        beginBlock(persist = false)
        status("Waiting for changes...")
      }
      PhaseEnd.None -> Unit
    }
  }

  /**
   * Clears any pinned footer. Safety net for shutdown hooks; also used between dev-server phases
   * when a health-check fails. Idempotent — no-op if nothing is pinned.
   */
  fun clearPinnedFooter() = run { discardBlock() }

  /**
   * Commits the current sub-group of lines and opens a fresh sub-block of the same type. Use inside
   * a [phase] body to insert a section boundary between two visually separate groups of output
   * without breaking out of the phase. The committed group is promoted to permanent scrollback, so
   * if the phase body later throws, only the new group is discarded.
   */
  fun nextSection() = run { nextSection() }

  // ── Output ─────────────────────────────────────────────────────────

  private fun emit(text: String) = run { emit(text) }

  /** Prints a server/proxy log line above the pinned footer. */
  fun serverLog(line: String) = run { serverLog(line) }

  // ── Out-of-block primitives ────────────────────────────────────────

  fun cancelled() = run { cancelled("  ${yellow("⚠")}  ${dim("Cancelled")}") }

  fun header(version: String) = run {
    header("  ${cyan("✈")}  ${bold(cyan("PaperPlane"))} ${dim("v$version")}")
  }

  /**
   * Bold subtitle line printed directly under [header]. Bypasses the block system since it appears
   * before any block activity.
   */
  fun subtitle(text: String) = run { subtitle("  ${bold(brightWhite(text))}") }

  /**
   * Marks the end of a "view". Emits one trailing blank line so the shell prompt has breathing
   * room. Idempotent within a view (reset by [header]).
   */
  fun endView() = run { endView() }

  // ── Typed status emitters ──────────────────────────────────────────

  fun success(message: String, duration: String? = null) {
    val text =
        if (duration != null) "  ${green("✓")}  $message ${dim(duration)}"
        else "  ${green("✓")}  $message"
    emit(text)
  }

  fun error(message: String, duration: String? = null) {
    val text =
        if (duration != null) "  ${red("✗")}  $message ${dim(duration)}"
        else "  ${red("✗")}  $message"
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
   * a [spin] block is executing; otherwise falls back to a normal status emit.
   */
  fun spinSubstatus(text: String) = run { setSpinnerSubstatus(text) }

  /**
   * Runs [block] while showing a spinner with [message] pinned in the footer. Server/proxy logs
   * scroll above; the spinner stays at the bottom. Code inside [block] can call [spinSubstatus] to
   * update detail text.
   */
  fun <T> spin(message: String, block: () -> T): T {
    val autoBlock = lock.withLock {
      val auto = !state.isBlockActive
      if (auto) renderer.render(state.beginBlock(BlockState.BlockType.PERSIST))
      renderer.render(state.setSpinner(message, null))
      auto
    }

    val done = AtomicBoolean(false)
    val thread =
        Thread(
            {
              while (!done.get()) {
                Thread.sleep(SPINNER_FRAME_INTERVAL_MS)
                lock.withLock { if (!done.get()) renderer.render(state.tickSpinner()) }
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
        val ops = state.clearSpinner() + if (autoBlock) state.endBlock() else emptyList()
        renderer.render(ops)
      }
    }
  }
}

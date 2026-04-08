package dev.paperplane.cli.ui

import org.jline.utils.NonBlockingReader

/**
 * The single injection seam for all terminal I/O in the CLI.
 *
 * Owns raw `System.out` writes, JLine raw-mode lifecycle, and the [NonBlockingReader] used by
 * [InteractivePrompts]. Every component that used to call `TerminalUI.*` / `InteractivePrompts.*`
 * now takes a [TerminalUI] or [InteractivePrompts] constructed from one of these — production code
 * uses [AnsiTerminal] (wraps `System.out` + JLine), tests use a fake.
 *
 * The separation exists so that:
 * - CLI output can be captured and asserted on in unit tests (no stdout capture, no ANSI regex)
 * - interactive prompt input can be scripted from a test reader queue
 * - dev-server phase transitions, cancellation-rollback paths, and block composition become
 *   directly testable without a real terminal
 *
 * Concurrency: implementations are single-writer by convention. [TerminalUI] holds a lock around
 * its own transitions before calling into the terminal; [InteractivePrompts] holds a
 * terminal-scoped lock around raw-mode entry/exit.
 */
interface Terminal {
  /** True when attached to an interactive TTY (controls footer-pinning and raw-mode paths). */
  val isTty: Boolean

  /** Prints [text] without a trailing newline. Used for raw ANSI sequences and partial lines. */
  fun write(text: String)

  /** Prints [text] followed by a newline. With the default empty string, emits a blank line. */
  fun writeLine(text: String = "")

  /** Flushes the output buffer. Required after raw cursor/clear sequences before the next read. */
  fun flush()

  /**
   * Enters raw mode (line-buffering off, ISIG cleared so Ctrl+C arrives as a byte rather than
   * SIGINT). Close the returned handle to restore the previous terminal state. No-op on non-TTY
   * implementations.
   */
  fun enterRawMode(): AutoCloseable

  /** Returns a non-blocking reader for raw-mode keystroke consumption. */
  fun reader(): NonBlockingReader
}

package dev.paperplane.cli.ui

/**
 * Package-private I/O adapter — the single point of write access in the `cli.ui` package.
 *
 * Wraps a [Terminal] and exposes the minimal primitive vocabulary ([writeLine], [write], [flush])
 * that [BlockRenderer] and [InteractivePrompts] consume. The layer exists so a CI grep can verify
 * nothing else in this package calls `System.out` directly — every raw write goes through here, and
 * [Writer] in turn delegates to an injected [Terminal].
 *
 * One instance is constructed by [TerminalUI] (and another by [InteractivePrompts]) from the shared
 * production [AnsiTerminal]. Tests construct a [Writer] over a fake terminal.
 */
internal class Writer(private val terminal: Terminal) {
  /** Prints [text] followed by a newline. With no argument, emits a blank line. */
  fun writeLine(text: String = "") {
    terminal.writeLine(text)
  }

  /** Prints [text] without a trailing newline. */
  fun write(text: String) {
    terminal.write(text)
  }

  /** Flushes the underlying terminal. */
  fun flush() {
    terminal.flush()
  }
}

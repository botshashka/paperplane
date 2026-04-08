package dev.paperplane.cli.ui

/**
 * The single point of `System.out` access for the `cli.ui` package.
 *
 * Every direct print/println in [TerminalUI] and [InteractivePrompts] funnels through one of the
 * methods here. This is the I/O primitive layer — it owns no state and enforces no invariants;
 * the block-state flags (`needsSeparator`, `viewClosed`, etc.) still live on [TerminalUI].
 *
 * The point is enforcement: a CI grep can verify nothing else in this package touches `System.out`,
 * so adding a new render primitive means extending [Writer] in one place rather than threading flag
 * updates through six call sites.
 *
 * Step 6 of the deferred refactors will convert this to a non-singleton injected behind a
 * `Terminal` interface — the method vocabulary here is what that interface will mirror.
 */
internal object Writer {
  /** Prints [text] followed by a newline. With no argument, just emits a blank line. */
  fun writeLine(text: String = "") {
    println(text)
  }

  /** Prints [text] without a trailing newline. Used for raw ANSI sequences and partial lines. */
  fun write(text: String) {
    print(text)
  }

  /** Flushes `System.out`. Required after raw cursor/clear sequences before the next read. */
  fun flush() {
    System.out.flush()
  }
}

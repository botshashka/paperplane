package dev.paperplane.cli.ui

/**
 * Pure data describing a single terminal-render command emitted by [BlockState].
 *
 * The split exists so [BlockState] can be unit-tested without a real terminal: each transition
 * returns a `List<RenderOp>` whose contents are compared with `assertEquals`. [BlockRenderer] is
 * the only consumer that translates these into [Writer] calls.
 *
 * Vocabulary intentionally minimal — two ops cover every existing render path:
 * - [ClearFooter] erases the previously-pinned footer (n lines worth of cursor-up + clear-line
 *   ANSI sequences plus a flush)
 * - [WriteLine] prints text plus a newline (with no argument: a blank line)
 *
 * Add new variants only when [BlockState] genuinely needs to express a new primitive that can't
 * be composed from these two.
 */
internal sealed class RenderOp {
  /** Erase [lineCount] lines of pinned footer from the terminal. No-op when count is zero. */
  data class ClearFooter(val lineCount: Int) : RenderOp()

  /** Print [text] followed by a newline. With the default empty string, just emits a blank line. */
  data class WriteLine(val text: String = "") : RenderOp()
}

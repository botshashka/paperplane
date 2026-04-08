package dev.paperplane.cli.ui

import dev.paperplane.cli.ui.RenderOp.ClearFooter
import dev.paperplane.cli.ui.RenderOp.WriteLine

/**
 * Stateless consumer of [RenderOp]s. Translates each op into [Writer] calls.
 *
 * No state, no flags — every render decision is made upstream by [BlockState]. This separation
 * is what makes [BlockState] unit-testable: tests stop at the op list and never need to touch
 * a real terminal or capture stdout.
 *
 * Step 6 of the deferred refactors will replace the static [Writer] reference here with a
 * constructor-injected `Terminal` so [BlockRenderer] itself becomes injectable.
 */
internal object BlockRenderer {
  fun render(ops: List<RenderOp>) {
    for (op in ops) {
      when (op) {
        is ClearFooter -> clearFooter(op.lineCount)
        is WriteLine -> Writer.writeLine(op.text)
      }
    }
  }

  private fun clearFooter(lineCount: Int) {
    if (lineCount <= 0) return
    Writer.write("\u001b[${lineCount}A")
    repeat(lineCount) { Writer.write("\u001b[2K\n") }
    Writer.write("\u001b[${lineCount}A")
    Writer.flush()
  }
}

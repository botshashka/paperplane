package dev.paperplane.cli.ui

import dev.paperplane.cli.ui.RenderOp.ClearFooter
import dev.paperplane.cli.ui.RenderOp.WriteLine

/**
 * Stateless consumer of [RenderOp]s. Translates each op into [Writer] calls.
 *
 * No state, no flags — every render decision is made upstream by [BlockState]. This separation is
 * what makes [BlockState] unit-testable: tests stop at the op list and never need to touch a real
 * terminal or capture stdout.
 */
internal class BlockRenderer(private val writer: Writer) {
  fun render(ops: List<RenderOp>) {
    for (op in ops) {
      when (op) {
        is ClearFooter -> clearFooter(op.lineCount)
        is WriteLine -> writer.writeLine(op.text)
      }
    }
  }

  private fun clearFooter(lineCount: Int) {
    if (lineCount <= 0) return
    writer.write("\u001b[${lineCount}A")
    repeat(lineCount) { writer.write("\u001b[2K\n") }
    writer.write("\u001b[${lineCount}A")
    writer.flush()
  }
}

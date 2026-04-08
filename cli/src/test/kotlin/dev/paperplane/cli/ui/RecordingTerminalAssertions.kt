package dev.paperplane.cli.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

/**
 * Structural matchers over [RecordingTerminal.writes] that assert on high-level properties (order,
 * separators, presence, trailing view closure) while staying tolerant to exact-byte formatting
 * changes.
 *
 * Exact-string assertions belong inline in tests via `assertEquals(listOf(...), writes)`. These
 * helpers are for the cases where the shape of the output matters more than its bytes — e.g.
 * "dev-server startup emits build lines before server-info lines, separated by one blank."
 *
 * Caveat: [RecordingTerminal.writes] records every [Terminal.writeLine] call in order, including
 * pinned-footer lines that were later cleared via cursor ANSI sequences. It is a write log, not a
 * screen capture. For most visual-regression tests this is the right granularity — the sequence of
 * writes is deterministic and a regression in footer pinning will show up here even if the final
 * screen looks unchanged. Tests that need the screen-level view should fall back to parsing
 * [RecordingTerminal.raw].
 */

/** True if this write line is either empty or contains only whitespace. */
private fun String.isBlankLine(): Boolean = isBlank()

/**
 * Assert that [fragments] appear in [writes] in the given order, each fragment matched as a
 * substring against some line. Other lines may appear between them. Fails with a message pointing
 * at the first missing fragment.
 */
fun RecordingTerminal.assertEmittedInOrder(vararg fragments: String) {
  val lines = writes
  var cursor = 0
  for (fragment in fragments) {
    val idx = (cursor until lines.size).firstOrNull { lines[it].contains(fragment) }
    if (idx == null) {
      fail<Unit>(
          buildString {
            appendLine("Expected fragment not found in order: \"$fragment\"")
            appendLine("Looking after line index $cursor")
            appendLine("Full captured writes:")
            lines.forEachIndexed { i, line -> appendLine("  $i: $line") }
          }
      )
    }
    cursor = idx!! + 1
  }
}

/**
 * Assert that exactly [blankLines] blank lines appear between the *final* occurrence of [before]
 * and the next occurrence of [after]. Uses the last occurrence of [before] because pinned-footer
 * redraws emit the same line multiple times in the write log — the final commit is what matters
 * visually. Fails if either anchor is missing, if [after] never appears after [before], or if the
 * blank-line count between them doesn't match.
 */
fun RecordingTerminal.assertSeparatorBetween(before: String, after: String, blankLines: Int = 1) {
  val lines = writes
  val beforeIdx = lines.indexOfLast { it.contains(before) }
  if (beforeIdx == -1) {
    fail<Unit>("Before-anchor \"$before\" not found in writes: $lines")
    return
  }
  val afterIdx = (beforeIdx + 1 until lines.size).firstOrNull { lines[it].contains(after) }
  if (afterIdx == null) {
    fail<Unit>("After-anchor \"$after\" not found after \"$before\". Writes: $lines")
    return
  }
  val between = lines.subList(beforeIdx + 1, afterIdx)
  val blanks = between.count { it.isBlankLine() }
  val nonBlanks = between.count { !it.isBlankLine() }
  if (nonBlanks != 0) {
    fail<Unit>(
        "Expected only blank lines between \"$before\" and \"$after\", found non-blank: $between"
    )
    return
  }
  assertEquals(
      blankLines,
      blanks,
      "Expected $blankLines blank line(s) between \"$before\" and \"$after\", found $blanks. " +
          "Full writes: $lines",
  )
}

/**
 * Assert that no two consecutive blank lines appear anywhere in [writes]. Catches double-blank
 * regressions (the bug we just debugged in the shutdown-hook path).
 */
fun RecordingTerminal.assertNoConsecutiveBlanks() {
  val lines = writes
  for (i in 1 until lines.size) {
    if (lines[i].isBlankLine() && lines[i - 1].isBlankLine()) {
      fail<Unit>(
          buildString {
            appendLine("Consecutive blank lines at indices ${i - 1} and $i.")
            appendLine("Full writes:")
            lines.forEachIndexed { idx, line ->
              appendLine("  $idx: ${if (line.isBlank()) "<blank>" else line}")
            }
          }
      )
    }
  }
}

/**
 * Assert that the captured output ends with exactly one trailing blank line — the signature of a
 * clean [TerminalUI.endView] call at command completion.
 */
fun RecordingTerminal.assertViewClosed() {
  val lines = writes
  assertTrue(lines.isNotEmpty(), "No writes captured; cannot assert view closed")
  assertTrue(
      lines.last().isBlankLine(),
      "Expected last line to be blank (endView marker), got: \"${lines.last()}\"",
  )
}

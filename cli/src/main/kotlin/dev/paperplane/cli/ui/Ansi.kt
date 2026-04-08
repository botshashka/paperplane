package dev.paperplane.cli.ui

/** ANSI color and cursor helpers. Honors NO_COLOR. */
internal object Ansi {
  private val noColor = System.getenv("NO_COLOR") != null
  val useColor: Boolean = !noColor

  private const val RESET = "\u001b[0m"
  private const val BOLD = "\u001b[1m"
  private const val DIM = "\u001b[2m"
  private const val RED = "\u001b[31m"
  private const val GREEN = "\u001b[32m"
  private const val YELLOW = "\u001b[33m"
  private const val CYAN = "\u001b[36m"
  private const val BRIGHT_WHITE = "\u001b[97m"

  // Cursor control. These are not color codes and are emitted unconditionally —
  // NO_COLOR does not affect cursor movement.
  const val CLEAR_LINE = "\u001b[2K"
  const val HIDE_CURSOR = "\u001b[?25l"
  const val SHOW_CURSOR = "\u001b[?25h"
  const val CR = "\r"

  fun cursorUp(n: Int = 1): String = "\u001b[${n}A"

  fun cursorDown(n: Int = 1): String = "\u001b[${n}B"

  fun cursorRight(n: Int): String = "\u001b[${n}C"

  fun color(code: String, text: String): String = if (noColor) text else "$code$text$RESET"

  fun bold(text: String) = color(BOLD, text)

  fun dim(text: String) = color(DIM, text)

  fun green(text: String) = color(GREEN, text)

  fun red(text: String) = color(RED, text)

  fun yellow(text: String) = color(YELLOW, text)

  fun cyan(text: String) = color(CYAN, text)

  fun brightWhite(text: String) = color(BRIGHT_WHITE, text)
}

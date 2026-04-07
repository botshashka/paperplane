package dev.paperplane.cli.ui

/** ANSI color helpers. Honors NO_COLOR. */
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

  fun color(code: String, text: String): String = if (noColor) text else "$code$text$RESET"

  fun bold(text: String) = color(BOLD, text)

  fun dim(text: String) = color(DIM, text)

  fun green(text: String) = color(GREEN, text)

  fun red(text: String) = color(RED, text)

  fun yellow(text: String) = color(YELLOW, text)

  fun cyan(text: String) = color(CYAN, text)

  fun brightWhite(text: String) = color(BRIGHT_WHITE, text)
}

package dev.paperplane.cli.server

import dev.paperplane.cli.ui.Ansi

private val serverLineRegex = Regex("""\[[\d:]+] \[([^]]+)] (.+)""")

/** Formats a raw server log line — dimming the thread prefix if color is supported. */
internal fun formatServerLine(line: String): String {
  val match = serverLineRegex.find(line) ?: return line
  val (thread, message) = match.destructured
  return if (Ansi.useColor) "\u001b[2m[$thread]\u001b[0m $message" else "[$thread] $message"
}

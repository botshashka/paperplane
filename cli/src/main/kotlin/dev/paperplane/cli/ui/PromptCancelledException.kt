package dev.paperplane.cli.ui

/**
 * Thrown from [TerminalUI.prompt] / [TerminalUI.select] when the user cancels via Ctrl+C or ESC.
 * Commands catch this at their boundary and translate to a `ProgramResult(130)` so Clikt unwinds
 * cleanly (unlike `System.exit`, which skips `finally` blocks).
 */
class PromptCancelledException : kotlin.coroutines.cancellation.CancellationException("User cancelled prompt")

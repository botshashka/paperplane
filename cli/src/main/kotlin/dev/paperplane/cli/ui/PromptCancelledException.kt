package dev.paperplane.cli.ui

/**
 * Thrown from [InteractivePrompts.prompt] / [InteractivePrompts.select] when the user cancels via
 * Ctrl+C or ESC. Commands catch this at their boundary and translate to a `ProgramResult(
 * EXIT_CANCELLED)` so Clikt unwinds cleanly (unlike `System.exit`, which skips `finally` blocks).
 */
class PromptCancelledException :
    kotlin.coroutines.cancellation.CancellationException("User cancelled prompt")

/** POSIX convention: 128 + SIGINT(2) = 130. Used by commands to exit after Ctrl+C/ESC. */
const val EXIT_CANCELLED = 130

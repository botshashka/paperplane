package dev.paperplane.cli.util

private const val FORMAT_THRESHOLD_MS = 1000

/** Formats a millisecond duration as "123ms" below one second, or "1.2s" at or above. */
internal fun formatDurationMs(ms: Long): String =
    if (ms >= FORMAT_THRESHOLD_MS) "%.1fs".format(ms / FORMAT_THRESHOLD_MS.toDouble())
    else "${ms}ms"

package dev.paperplane.companion

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Move [src] to [dst] atomically when the underlying filesystem supports it, falling back to a
 * non-atomic replace when it doesn't. Always passes `REPLACE_EXISTING` so callers don't need to
 * delete [dst] up front. Mirror of the CLI's `atomicMoveOrFallback` — the modules share no code.
 */
internal fun atomicMoveOrFallback(src: Path, dst: Path) {
  try {
    Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
  }
}

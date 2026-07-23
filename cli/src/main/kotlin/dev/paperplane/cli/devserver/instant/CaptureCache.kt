package dev.paperplane.cli.devserver.instant

import java.io.File

/**
 * Byte and CRC reuse across successive [BuildCandidate.capture]s. Each rebuild re-reads the whole
 * output tree even though almost nothing changed — and an escalated rebuild captures twice (the
 * lane's attempt, then the mode's confirm over near-identical output). A file whose (size,
 * lastModified) both match the previous walk reuses the previously read result instead of touching
 * disk again. Sharing arrays is safe because captures are immutable by convention — nothing mutates
 * a snapshot's bytes.
 *
 * **The freshness guard is what keeps reuse from ever producing a stale verdict.** Filesystem
 * mtimes have finite resolution, so "same size, same mtime" does not prove "same bytes" for a file
 * rewritten within one resolution tick. A rewrite always stamps a near-now mtime, though — so a
 * file whose mtime is within [FRESHNESS_GUARD_MS] of the capture is unconditionally re-read, and
 * reuse is only ever offered for files provably untouched since well before this capture. Missing
 * the cache costs one file read; trusting it wrongly would hand the classifier bytes nobody built,
 * the exact silent-staleness failure this tool exists to prevent.
 */
internal class CaptureCache {

  private class Entry(val size: Long, val lastModified: Long, val bytes: ByteArray, val crc: Long)

  private val entries = HashMap<String, Entry>()

  /** The bytes of [file], reused from the previous capture when provably unchanged. */
  fun read(file: File): ByteArray = entry(file).bytes

  /** The CRC32 of [file]'s bytes, same reuse contract as [read]. */
  fun crc32(file: File): Long = entry(file).crc

  /**
   * Drops entries for files outside [seen] — deleted or renamed outputs would otherwise pin their
   * bytes for the rest of the session. Called by the capture with the paths it walked.
   */
  fun retainOnly(seen: Set<String>) {
    entries.keys.retainAll(seen)
  }

  private fun entry(file: File): Entry {
    val path = file.absolutePath
    val size = file.length()
    val lastModified = file.lastModified()
    val cached = entries[path]
    if (cached != null && cached.provablyStill(size, lastModified)) return cached
    val bytes = file.readBytes()
    // Stat before read: if the file changes mid-read, the recorded mtime is by then stale-fresh
    // and the next capture re-reads it — the race degrades to a cache miss, never to reuse.
    val entry = Entry(size, lastModified, bytes, BuildCandidate.crc32(bytes))
    entries[path] = entry
    return entry
  }

  /** Same stat AND old enough that mtime resolution can't be hiding a rewrite. */
  private fun Entry.provablyStill(size: Long, lastModified: Long): Boolean =
      this.size == size &&
          this.lastModified == lastModified &&
          lastModified < System.currentTimeMillis() - FRESHNESS_GUARD_MS

  private companion object {
    /**
     * Files modified within this window of the capture are always re-read. Generous relative to any
     * real filesystem's mtime resolution (FAT's 2 s is the historical worst case).
     */
    const val FRESHNESS_GUARD_MS = 3_000L
  }
}

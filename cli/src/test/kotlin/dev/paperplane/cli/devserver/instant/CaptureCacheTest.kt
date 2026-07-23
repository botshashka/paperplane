package dev.paperplane.cli.devserver.instant

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CaptureCacheTest {

  @TempDir lateinit var tempDir: File

  private fun fileWith(name: String, bytes: ByteArray, mtimeAgoMs: Long): File =
      File(tempDir, name).apply {
        writeBytes(bytes)
        check(setLastModified(System.currentTimeMillis() - mtimeAgoMs))
      }

  @Test
  fun `an unchanged old file reuses the previously read array`() {
    val cache = CaptureCache()
    val file = fileWith("Logic.class", byteArrayOf(1, 2, 3), mtimeAgoMs = 60_000)

    val first = cache.read(file)
    val second = cache.read(file)

    assertSame(first, second, "an unchanged file must not be re-read or re-allocated")
    assertEquals(BuildCandidate.crc32(first), cache.crc32(file))
  }

  @Test
  fun `a rewritten file is re-read`() {
    val cache = CaptureCache()
    val file = fileWith("Logic.class", byteArrayOf(1, 2, 3), mtimeAgoMs = 60_000)
    cache.read(file)

    file.writeBytes(byteArrayOf(9, 9, 9, 9))
    check(file.setLastModified(System.currentTimeMillis() - 30_000))

    assertTrue(cache.read(file).contentEquals(byteArrayOf(9, 9, 9, 9)))
  }

  @Test
  fun `a freshly modified file is never reused even when size and mtime match`() {
    // Filesystem mtimes have finite resolution: "same size, same mtime" does not prove "same
    // bytes" for a file rewritten within one resolution tick. A rewrite always stamps a near-now
    // mtime, so near-now entries must be re-read unconditionally — trusting one would hand the
    // classifier bytes nobody built.
    val cache = CaptureCache()
    val file = File(tempDir, "Fresh.class").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    val first = cache.read(file)
    // Same size, and the mtime is whatever "now" stamped both times.
    file.writeBytes(byteArrayOf(4, 5, 6))
    check(file.setLastModified(file.lastModified()))

    val second = cache.read(file)
    assertNotSame(first, second, "a near-now mtime must force a re-read")
    assertTrue(second.contentEquals(byteArrayOf(4, 5, 6)))
  }

  @Test
  fun `retainOnly drops entries for files no longer in the walk`() {
    val cache = CaptureCache()
    val kept = fileWith("Kept.class", byteArrayOf(1), mtimeAgoMs = 60_000)
    val gone = fileWith("Gone.class", byteArrayOf(2), mtimeAgoMs = 60_000)
    val keptBytes = cache.read(kept)
    val goneBytes = cache.read(gone)

    cache.retainOnly(setOf(kept.absolutePath))

    assertSame(keptBytes, cache.read(kept))
    // Deleted-then-recreated under the same name: the entry must be gone, not resurrected.
    assertNotSame(goneBytes, cache.read(gone))
  }

  @Test
  fun `capture with a cache produces the same snapshot as without`() {
    val classesDir = File(tempDir, "classes").apply { mkdirs() }
    val resourcesDir = File(tempDir, "resources").apply { mkdirs() }
    File(classesDir, "com/example").mkdirs()
    File(classesDir, "com/example/Logic.class").apply {
      writeBytes(byteArrayOf(1, 2, 3))
      setLastModified(System.currentTimeMillis() - 60_000)
    }
    File(resourcesDir, "config.yml").apply {
      writeBytes("a: 1".toByteArray())
      setLastModified(System.currentTimeMillis() - 60_000)
    }
    val cache = CaptureCache()

    val plain = BuildCandidate.capture(listOf(classesDir), resourcesDir)
    val cachedFirst = BuildCandidate.capture(listOf(classesDir), resourcesDir, cache)
    val cachedSecond = BuildCandidate.capture(listOf(classesDir), resourcesDir, cache)

    assertTrue(
        plain.classes
            .getValue("com.example.Logic")
            .contentEquals(cachedFirst.classes.getValue("com.example.Logic"))
    )
    assertEquals(plain.resourceCrcs, cachedFirst.resourceCrcs)
    assertSame(
        cachedFirst.classes.getValue("com.example.Logic"),
        cachedSecond.classes.getValue("com.example.Logic"),
        "the second capture must reuse the first's array",
    )
    assertFalse(
        plain.classes.getValue("com.example.Logic") ===
            cachedFirst.classes.getValue("com.example.Logic")
    )
  }
}

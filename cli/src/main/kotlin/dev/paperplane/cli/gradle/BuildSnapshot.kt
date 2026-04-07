package dev.paperplane.cli.gradle

import java.io.File
import java.util.zip.CRC32
import kotlin.io.DEFAULT_BUFFER_SIZE

/**
 * Tracks .class file hashes between builds to detect what changed. Used to determine whether a
 * change is suitable for hot-swap (Level 2) vs requiring a full classloader reload (Level 1).
 */
class BuildSnapshot(private val classesDir: File) {

  /** Takes a snapshot of all .class files and their CRC32 hashes. */
  fun take(): Map<String, Long> {
    if (!classesDir.exists()) return emptyMap()
    return classesDir
        .walkTopDown()
        .filter { it.isFile && it.extension == "class" }
        .associate { it.relativeTo(classesDir).path to crc32(it) }
  }

  companion object {
    fun diff(previous: Map<String, Long>, current: Map<String, Long>): ClassChanges {
      val modified =
          current.keys
              .intersect(previous.keys)
              .filter { current[it] != previous[it] }
              .map { pathToFqcn(it) }
      val added = (current.keys - previous.keys).map { pathToFqcn(it) }
      val removed = (previous.keys - current.keys).map { pathToFqcn(it) }
      return ClassChanges(modified, added, removed)
    }

    fun crc32(file: File): Long {
      val crc = CRC32()
      val buf = ByteArray(DEFAULT_BUFFER_SIZE)
      file.inputStream().use { inp ->
        var n: Int
        while (inp.read(buf).also { n = it } != -1) crc.update(buf, 0, n)
      }
      return crc.value
    }

    fun pathToFqcn(path: String): String {
      return path.removeSuffix(".class").replace('/', '.').replace('\\', '.')
    }
  }
}

/** Describes what .class files changed between two builds. */
data class ClassChanges(
    val modified: List<String>, // FQCNs of classes with changed bytecode
    val added: List<String>, // FQCNs of new classes
    val removed: List<String>, // FQCNs of deleted classes
) {
  /** True if no classes were added or removed (only modified). */
  val noNewOrRemovedClasses: Boolean
    get() = added.isEmpty() && removed.isEmpty()
}

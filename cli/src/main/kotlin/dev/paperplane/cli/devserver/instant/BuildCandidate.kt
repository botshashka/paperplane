package dev.paperplane.cli.devserver.instant

import java.io.File
import java.util.zip.CRC32

/**
 * An immutable snapshot of one build's output: every `.class` file's bytes keyed by FQCN, and a CRC
 * per resource file. Captured after each successful compile; the [BaselineTracker] promotes a
 * candidate to baseline only once it is confirmed loaded in the live server.
 *
 * Holding full class bytes (not just hashes) is deliberate: the classifier needs old bytes for
 * structural comparison, and the patch payload needs new bytes for the wire — plugin outputs are
 * small enough that two full snapshots in memory are noise.
 */
class BuildCandidate(
    val classes: Map<String, ByteArray>,
    val resourceCrcs: Map<String, Long>,
) {
  fun classCrc(fqcn: String): Long = classes[fqcn]?.let { crc32(it) } ?: 0L

  companion object {
    /**
     * Reads the build output from disk. [classesDirs] are walked for `.class` files (FQCN from the
     * relative path, matching `BuildSnapshot.pathToFqcn`); [resourcesDir] (may not exist) is walked
     * for everything else.
     */
    fun capture(classesDirs: List<File>, resourcesDir: File?): BuildCandidate {
      val classes = mutableMapOf<String, ByteArray>()
      for (dir in classesDirs) {
        if (!dir.exists()) continue
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { file ->
              val fqcn =
                  file.relativeTo(dir).path.removeSuffix(".class").replace(File.separatorChar, '.')
              classes[fqcn] = file.readBytes()
            }
      }
      val resources = mutableMapOf<String, Long>()
      if (resourcesDir != null && resourcesDir.exists()) {
        resourcesDir
            .walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
              resources[file.relativeTo(resourcesDir).path.replace(File.separatorChar, '/')] =
                  crc32(file.readBytes())
            }
      }
      return BuildCandidate(classes, resources)
    }

    fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
  }
}

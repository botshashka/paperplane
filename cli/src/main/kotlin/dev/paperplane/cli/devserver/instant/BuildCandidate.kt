package dev.paperplane.cli.devserver.instant

import java.io.File
import java.util.zip.CRC32

/**
 * An immutable snapshot of one build's output: every `.class` file's bytes keyed by FQCN, a CRC per
 * resource file, and the output directories the snapshot was read from. Captured after each
 * successful compile; the [BaselineTracker] promotes a candidate to baseline only once it is
 * confirmed loaded in the live server.
 *
 * Holding full class bytes (not just hashes) is deliberate: the classifier needs old bytes for
 * structural comparison, and the patch payload needs new bytes for the wire — plugin outputs are
 * small enough that two full snapshots in memory are noise.
 *
 * [sourceDirs] is part of the snapshot's identity, not decoration. A mid-session build-config edit
 * (adding `kotlin("jvm")`, moving `sourceSets`) moves the output dirs, and the next capture then
 * reads a *different* tree: every class in it looks absent from the baseline, so the classifier
 * would report a change-set of phantom new classes the companion no-ops on and the CLI reports as
 * patched. Comparing the dirs turns that into an escalation.
 */
internal class BuildCandidate(
    val classes: Map<String, ByteArray>,
    val resourceCrcs: Map<String, Long>,
    val sourceDirs: List<String> = emptyList(),
) {
  fun classCrc(fqcn: String): Long = classes[fqcn]?.let { crc32(it) } ?: 0L

  companion object {
    /**
     * Reads the build output from disk. [classesDirs] are walked for `.class` files (FQCN from the
     * relative path); [resourcesDir] (may not exist) is walked for everything else.
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
      return BuildCandidate(classes, resources, sourceDirs(classesDirs, resourcesDir))
    }

    /**
     * The capture's input tree, normalized so an equal list really does mean "same directories":
     * absolute paths, sorted (a metadata refresh may reorder the classes dirs without any of them
     * moving), resources dir tagged so it can't collide with a classes dir.
     */
    private fun sourceDirs(classesDirs: List<File>, resourcesDir: File?): List<String> =
        (classesDirs.map { "classes:${it.absolutePath}" } +
                listOfNotNull(resourcesDir?.let { "resources:${it.absolutePath}" }))
            .sorted()

    fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
  }
}

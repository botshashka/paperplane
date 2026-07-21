package dev.paperplane.cli.devserver.instant

import dev.paperplane.cli.gradle.ProjectMetadata

/**
 * Curated list of frameworks known to discover plugin methods reflectively by naming convention,
 * scanned once and cached — the one silent-staleness residual the classifier's annotation rule
 * cannot see (an *unannotated* added method that such a framework would have picked up at scan
 * time stays invisible forever after a redefine).
 *
 * When one is present, the instant tier's capability is capped to [RedefineCapability.BODY_ONLY]
 * for the session: body edits stay instant, structural admission is off. The list is deliberately
 * small and grows only on concrete evidence of scan-once-by-name behavior — annotation-driven
 * registries (Bukkit events, ACF, Cloud) are already covered by the classifier's annotation gate
 * and do not belong here.
 */
object ReflectionFrameworkList {
  private val markers = mapOf("skript" to "Skript")

  /**
   * The display name of the first matched framework, or null. Matches against dependency-plugin
   * names ([ProjectMetadata.depend]/[ProjectMetadata.softdepend]) and runtime-classpath jar
   * paths.
   */
  fun match(metadata: ProjectMetadata): String? {
    val haystacks =
        (metadata.depend + metadata.softdepend + metadata.runtimeClasspath).map { it.lowercase() }
    for ((marker, display) in markers) {
      if (haystacks.any { it.contains(marker) }) return display
    }
    return null
  }
}

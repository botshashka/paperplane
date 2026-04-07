package dev.paperplane.cli

object Versions {
  // The companion plugin's api-version in plugin.yml must match the minimum of this set.
  val SUPPORTED_API_VERSIONS = setOf("1.18", "1.19", "1.20", "1.21")

  // Offline fallback — used only when PaperMC API is unreachable
  const val PAPER_FALLBACK = "1.21.10"

  // Velocity major series pin — prevents pulling e.g. Velocity 4.x with breaking changes
  const val VELOCITY_SERIES = "3"

  // Defaults for ppl create scaffolding only (new projects can change these immediately)
  const val GRADLE_WRAPPER = "9.4.1"
  const val KOTLIN = "2.3.20"
  const val SHADOW = "9.4.1"
  const val MOCKBUKKIT = "4.108.0"
  const val JUNIT = "5.11.4"
  const val SPOTLESS = "7.0.2"

  /** PaperPlane's own version, read from JAR manifest at runtime. */
  fun paperplaneVersion(): String = Versions::class.java.`package`?.implementationVersion ?: "dev"

  /** Extracts the api-version from a full MC version. "1.21.10" → "1.21" */
  fun apiVersion(mcVersion: String): String = mcVersion.split(".").take(2).joinToString(".")

  /** Derives the MockBukkit artifact name. "1.21.10" → "mockbukkit-v1.21" */
  fun mockbukkitArtifact(mcVersion: String): String = "mockbukkit-v${apiVersion(mcVersion)}"
}

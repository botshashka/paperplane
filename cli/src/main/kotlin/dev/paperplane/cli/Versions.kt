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

  /**
   * User-Agent for PaperMC Fill API requests. Fill rejects generic agents (curl/wget/etc.) and
   * requires a contact URL, so identify ourselves explicitly.
   */
  fun userAgent(): String =
      "paperplane/${paperplaneVersion()} (+https://github.com/botshashka/paperplane)"

  /** Extracts the api-version from a full MC version. "1.21.10" → "1.21" */
  fun apiVersion(mcVersion: String): String = mcVersion.split(".").take(2).joinToString(".")

  /** Derives the MockBukkit artifact name. "1.21.10" → "mockbukkit-v1.21" */
  fun mockbukkitArtifact(mcVersion: String): String = "mockbukkit-v${apiVersion(mcVersion)}"

  /**
   * Compares dotted numeric versions ("1.21.10" > "1.21.4" > "1.21"). Non-numeric or missing
   * segments sort as 0, so "1.21" < "1.21.1". Used to normalize Fill's grouped, newest-first
   * version lists into a plain ascending order.
   */
  fun compareVersions(a: String, b: String): Int {
    val pa = a.split(".")
    val pb = b.split(".")
    for (i in 0 until maxOf(pa.size, pb.size)) {
      val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
      val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
      if (x != y) return x - y
    }
    return 0
  }
}

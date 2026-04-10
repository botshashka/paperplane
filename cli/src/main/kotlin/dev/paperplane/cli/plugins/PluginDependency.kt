package dev.paperplane.cli.plugins

import kotlinx.serialization.Serializable

/**
 * A single entry in `server.plugins` in `paperplane.yml`. Each entry names exactly one source —
 * `modrinth` (Modrinth project slug) or `local` (filesystem path to a JAR). `version` is optional
 * for `modrinth`: when omitted, the resolver picks the latest version compatible with the project's
 * MC version. Ignored for `local`.
 *
 * Shape in YAML:
 * ```
 * plugins:
 *   - modrinth: placeholderapi
 *   - modrinth: vault
 *     version: "1.7.3"
 *   - local: ./libs/my-private-plugin.jar
 * ```
 *
 * A single data class with nullable source fields gives us this YAML shape naturally via kaml's
 * default serialization. A sealed hierarchy would need a custom serializer to match the same shape
 * (kaml's polymorphism uses a `type:` discriminator key, not bare source keys). Validation in
 * `init` enforces the "exactly one source" invariant.
 */
@Serializable
data class PluginDependency(
    val modrinth: String? = null,
    val local: String? = null,
    val version: String? = null,
) {
  init {
    val sources = listOfNotNull(modrinth, local)
    require(sources.size == 1) {
      "Plugin entry must specify exactly one of 'modrinth' or 'local', got: $this"
    }
    if (local != null) {
      require(version == null) { "'version' is not valid for 'local' plugin entries" }
    }
  }

  /**
   * Stable, canonical identifier for cache keys, lockfile keys, and comparisons. Always lowercase
   * so that `WorldEdit`, `worldedit`, and `WORLDEDIT` all collapse to a single entry —
   * case-insensitivity by construction rather than by comparison. For Modrinth this matches the
   * server-side canonical form; for local entries it normalizes the filename basename so
   * hand-edited configs and filesystems with mixed-case filenames stay consistent.
   */
  val slug: String
    get() = (modrinth ?: localSlug(local!!)).lowercase()

  val source: Source
    get() = if (modrinth != null) Source.MODRINTH else Source.LOCAL

  enum class Source(val key: String) {
    MODRINTH("modrinth"),
    LOCAL("local"),
  }

  companion object {
    /** Derives a stable slug from a local JAR path — the filename without the `.jar` extension. */
    fun localSlug(path: String): String {
      val name = java.io.File(path).name
      return name.removeSuffix(".jar")
    }

    /**
     * Constructs a Modrinth entry. Does NOT lowercase [slug] — Modrinth project IDs (e.g.
     * `1u6JkXh5`) are case-sensitive on the server, so a premature `lowercase()` here would break
     * bare-ID input. Canonicalization to the lowercase slug happens at the point where we call
     * `/project/{id}` and substitute the returned slug, which is always lowercase by Modrinth
     * convention. The [slug] getter elsewhere still normalizes reads for hand-edited configs
     * containing mixed-case slugs.
     */
    fun modrinth(slug: String, version: String? = null) =
        PluginDependency(modrinth = slug, version = version)

    fun local(path: String) = PluginDependency(local = path)
  }
}

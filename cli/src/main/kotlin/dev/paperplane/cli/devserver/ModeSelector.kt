package dev.paperplane.cli.devserver

import dev.paperplane.cli.Versions
import dev.paperplane.cli.config.DevMode
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.gradle.ProjectMetadata
import java.io.File

/**
 * One entry in the curated can't-late-load list: a dependency whose presence categorically rejects
 * hot-reload for the session, because the dependency binds to the plugin at server startup in a way
 * an in-place reload cannot undo or redo.
 *
 * Matching is two-pronged because dependency identity shows up in two shapes:
 * - [pluginNames] — exact, case-insensitive plugin names as they appear in `plugin.yml`
 *   `depend`/`softdepend` lists (from the `paperplane {}` extension via metadata.json).
 * - [artifactPattern] — matched against lowercase artifact identifiers: jar file names without the
 *   `.jar` extension (compile classpath, server `plugins/` dir) and `server.plugins` slugs from
 *   `paperplane.yml`. Anchor the pattern (`^…`) so e.g. `commandapi` doesn't fire on a user plugin
 *   that merely mentions it mid-name.
 *
 * [reason] is the human sentence that feeds the rejection banner and the tier report — write it so
 * it stands alone next to "Hot-reload is unavailable".
 */
internal data class LateLoadRule(
    val id: String,
    val pluginNames: Set<String>,
    val artifactPattern: Regex,
    val reason: String,
) {
  private val lowerNames = pluginNames.map { it.lowercase() }.toSet()

  fun matchesPluginName(name: String): Boolean = name.lowercase() in lowerNames

  fun matchesArtifact(identifier: String): Boolean =
      artifactPattern.containsMatchIn(identifier.lowercase())
}

/**
 * The curated seed list (concept §5): CommandAPI and ProtocolLib-hook patterns. Additions are cheap
 * — one [LateLoadRule] entry; the shape is the deliverable, not list completeness.
 */
internal val CURATED_LATE_LOAD_RULES: List<LateLoadRule> =
    listOf(
        LateLoadRule(
            id = "commandapi",
            pluginNames = setOf("CommandAPI"),
            artifactPattern = Regex("^commandapi"),
            reason =
                "CommandAPI wires commands into the server during startup and cannot " +
                    "re-register them for a plugin loaded in-place after boot",
        ),
        LateLoadRule(
            id = "protocollib",
            pluginNames = setOf("ProtocolLib"),
            artifactPattern = Regex("^protocollib"),
            reason =
                "ProtocolLib packet listeners bind to the plugin instance at registration " +
                    "and keep stale references across in-place reloads",
        ),
    )

/**
 * One categorical rejection of the requested mode: which rule fired ([ruleId]), the concrete
 * dependency/source that triggered it ([matchedBy], e.g. `plugin.yml depend 'ProtocolLib'`), and
 * the human [reason]. All three feed the rejection banner and the tier report.
 */
internal data class ModeRejection(val ruleId: String, val matchedBy: String, val reason: String)

/**
 * The outcome of session-start mode selection when it demoted: what the user asked for, what the
 * session actually runs, and why. Carried on the [DevSession] so the server-info tier report can
 * state the demotion — the §1 closing rule ("the tool always reports which tier it chose and why").
 */
internal data class SelectionReport(
    val requested: DevMode,
    val actual: DevMode,
    val rejections: List<ModeRejection>,
)

/**
 * Evaluates whether a requested dev mode is categorically unavailable for this session. Pure: scans
 * the dependency sources that are actually available and returns every rejection it finds, or an
 * empty list when the mode stands.
 *
 * Today only hot-reload (the fragile-fast tier) has categorical rejections — restart and blue-green
 * load natively with full fidelity. Fresh mode later slots in as another rejectable tier by adding
 * its rules here, without reshaping the callers.
 *
 * Sources scanned (each may legitimately be absent — a broken build has no metadata, a first run
 * has no server dir):
 * - `plugin.yml` `depend`/`softdepend` plugin names, from metadata.json.
 * - The project's runtime classpath jar names, from metadata.json — catches shaded dependencies
 *   like `commandapi-bukkit-shade`.
 * - `server.plugins` entries in `paperplane.yml` (Modrinth slugs / local jar paths).
 * - Jar names already present in the dev server's `plugins/` directory — catches jars dropped in by
 *   hand outside PaperPlane's managed list.
 */
internal class ModeSelector(private val rules: List<LateLoadRule> = CURATED_LATE_LOAD_RULES) {

  companion object {
    /**
     * Minimum Paper version for hot-reload. The companion host implements
     * `ConfiguredPluginClassLoader`, which only exists on Paper 1.19.3+. Restart and blue-green
     * load natively and have no such floor.
     */
    const val HOT_RELOAD_MIN_PAPER = "1.19.3"

    const val VERSION_FLOOR_RULE_ID = "paper-version-floor"

    /** Dotted-numeric shape ("1.21", "1.19.3") — anything else (e.g. "unknown") skips the floor. */
    private val PARSEABLE_VERSION = Regex("""^\d+(\.\d+)+$""")
  }

  fun rejections(
      mode: DevMode,
      config: PaperPlaneConfig,
      metadata: ProjectMetadata?,
      serverPluginsDir: File?,
  ): List<ModeRejection> {
    if (mode != DevMode.HOT_RELOAD) return emptyList()
    return dependencyRejections(config, metadata, serverPluginsDir) +
        listOfNotNull(versionFloorRejection(config, metadata))
  }

  private fun dependencyRejections(
      config: PaperPlaneConfig,
      metadata: ProjectMetadata?,
      serverPluginsDir: File?,
  ): List<ModeRejection> = rules.mapNotNull { rule ->
    val match = firstMatch(rule, config, metadata, serverPluginsDir) ?: return@mapNotNull null
    ModeRejection(rule.id, match, rule.reason)
  }

  /**
   * The first source [rule] fires on, as the human `matchedBy` string, or null when none do. The
   * sources are thunks so later ones (directory listing) aren't evaluated once an earlier one hits.
   */
  private fun firstMatch(
      rule: LateLoadRule,
      config: PaperPlaneConfig,
      metadata: ProjectMetadata?,
      serverPluginsDir: File?,
  ): String? =
      sequenceOf(
              {
                metadata?.depend?.firstOrNull(rule::matchesPluginName)?.let {
                  "plugin.yml depend '$it'"
                }
              },
              {
                metadata?.softdepend?.firstOrNull(rule::matchesPluginName)?.let {
                  "plugin.yml softdepend '$it'"
                }
              },
              {
                metadata
                    ?.runtimeClasspath
                    ?.map { File(it).name.removeSuffix(".jar") }
                    ?.firstOrNull(rule::matchesArtifact)
                    ?.let { "runtime classpath '$it'" }
              },
              {
                config.server.plugins
                    .map { it.slug }
                    .firstOrNull(rule::matchesArtifact)
                    ?.let { "dev-server plugin '$it' (paperplane.yml)" }
              },
              {
                serverPluginsDir
                    ?.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
                    ?.map { it.name.removeSuffix(".jar") }
                    ?.firstOrNull(rule::matchesArtifact)
                    ?.let { "server plugins/ jar '$it'" }
              },
          )
          .firstNotNullOfOrNull { it() }

  /**
   * The Paper version floor as a rejection rather than the throw it used to be
   * (`enforceHotReloadVersionFloor`), so it rides the same consent-based demotion flow as a
   * dependency hit. The version is derived the same way `resolveMcVersion` derives it; when neither
   * source yields a parseable version (broken build, no override) the floor abstains — supported-
   * version validation happens elsewhere.
   */
  private fun versionFloorRejection(
      config: PaperPlaneConfig,
      metadata: ProjectMetadata?,
  ): ModeRejection? {
    val mcVersion = config.server.version ?: metadata?.paperApiVersion ?: return null
    if (!PARSEABLE_VERSION.matches(mcVersion)) return null
    if (Versions.compareVersions(mcVersion, HOT_RELOAD_MIN_PAPER) >= 0) return null
    return ModeRejection(
        VERSION_FLOOR_RULE_ID,
        "Paper $mcVersion",
        "Hot-reload requires Paper $HOT_RELOAD_MIN_PAPER+ " +
            "(the first version with the plugin host API it needs)",
    )
  }
}

package dev.paperplane.cli.server

/** Multiline default config files written by [PaperServerManager.configure]. */
internal object ServerConfigs {
  // Filenames PaperPlane writes from paperplane.yml on every configure() call. Referenced both
  // here (to avoid stringly-typed writes) and from ServerSync (to skip them during blue/green
  // sync — they must come from paperplane.yml, not from the peer server).
  const val SERVER_PROPERTIES_FILE = "server.properties"
  const val BUKKIT_YML_FILE = "bukkit.yml"
  const val SPIGOT_YML_FILE = "spigot.yml"
  const val PAPER_CONFIG_DIR = "config"
  const val PAPER_GLOBAL_YML_FILE = "paper-global.yml"
  const val PAPER_WORLD_DEFAULTS_YML_FILE = "paper-world-defaults.yml"

  /** Top-level entries in the server directory that are PaperPlane-managed and never synced. */
  val MANAGED_CONFIG_FILES: Set<String> =
      setOf(SERVER_PROPERTIES_FILE, BUKKIT_YML_FILE, SPIGOT_YML_FILE, PAPER_CONFIG_DIR)

  /**
   * Keys that PaperPlane always forces. User overrides from `paperplane.yml` cannot change these —
   * they're essential for the dev server to function (port assignment, offline auth, velocity
   * transfer support).
   */
  val MANAGED_PROPERTY_KEYS: Set<String> = setOf("server-port", "accepts-transfers", "online-mode")

  /**
   * Default server.properties values. User-specified `server.properties` entries in paperplane.yml
   * are merged on top of these (user wins), except for [MANAGED_PROPERTY_KEYS] which always use
   * PaperPlane's values.
   */
  private val DEFAULT_SERVER_PROPERTIES: Map<String, String> =
      linkedMapOf(
          "online-mode" to "false",
          "view-distance" to "4",
          "simulation-distance" to "4",
          "level-type" to "flat",
          "spawn-protection" to "0",
          "max-players" to "2",
          "enable-command-block" to "true",
          "motd" to "PaperPlane Dev Server",
          "generate-structures" to "false",
          "accepts-transfers" to "true",
      )

  fun serverProperties(port: Int, userOverrides: Map<String, String> = emptyMap()): String {
    val merged = LinkedHashMap<String, String>(DEFAULT_SERVER_PROPERTIES)
    // User overrides win, except for managed keys which are silently ignored.
    for ((k, v) in userOverrides) {
      if (k !in MANAGED_PROPERTY_KEYS) merged[k] = v
    }
    // Force managed keys to their required values (always last — overrides anything).
    merged["server-port"] = port.toString()
    merged["accepts-transfers"] = "true"
    merged["online-mode"] = "false"
    return merged.entries.joinToString(separator = "\n", postfix = "\n") { (k, v) -> "$k=$v" }
  }

  val bukkitYml: String =
      """
      settings:
        allow-end: false
        connection-throttle: 0
      ticks-per:
        autosave: 0
      """
          .trimIndent() + "\n"

  val spigotYml: String =
      """
      settings:
        save-user-cache-on-stop-only: true
        bungeecord: false
      world-settings:
        default:
          verbose: false
      """
          .trimIndent() + "\n"

  val paperGlobalYml: String =
      """
      timings:
        enabled: false
      """
          .trimIndent() + "\n"

  val paperWorldDefaultsYml: String =
      """
      chunks:
        auto-save-interval: -1
      spawn:
        keep-spawn-loaded: false
      """
          .trimIndent() + "\n"
}

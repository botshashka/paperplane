package dev.paperplane.cli.server

/** Multiline default config files written by [PaperServerManager.configure]. */
internal object ServerConfigs {
  fun serverProperties(port: Int): String =
      """
        online-mode=false
        view-distance=4
        simulation-distance=4
        level-type=flat
        spawn-protection=0
        max-players=2
        enable-command-block=true
        server-port=$port
        motd=PaperPlane Dev Server
        generate-structures=false
        accepts-transfers=true
    """
          .trimIndent() + "\n"

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

package dev.paperplane.companion

import dev.paperplane.companion.host.InnerPluginHost
import dev.paperplane.companion.host.ReflectionProbe
import java.io.File
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.plugin.java.JavaPlugin

class CompanionPlugin : JavaPlugin() {
  private lateinit var errorCatcher: ErrorCatcher
  private lateinit var buildStatusBar: BuildStatusBar

  override fun onEnable() {
    try {
      // The host is built lazily on the first load request (hot-reload only). Probing Paper
      // internals here would fail-fast the whole companion on Paper versions that predate the
      // plugin-loader API — but restart/blue-green legitimately run on those and only need the
      // status bar / save protection / auto-op below. The probe happens inside this provider the
      // first time a load request arrives; BuildStatusBar reports a probe failure as `load-failed`.
      errorCatcher = ErrorCatcher(this)
      buildStatusBar =
          BuildStatusBar(
              this,
              hostProvider = {
                val probe = ReflectionProbe.probe(server)
                InnerPluginHost(server, javaClass.classLoader, probe, logger, hostPlugin = this)
              },
          )

      server.pluginManager.registerEvents(errorCatcher, this)
      server.pluginManager.registerEvents(AutoOpListener(serverRoot()), this)
      server.pluginManager.registerEvents(SaveProtectionListener(buildStatusBar), this)
      buildStatusBar.start()

      // Signal CLI that server is ready AFTER full initialization (worlds, protocol, datapacks).
      // ServerLoadEvent fires after all plugins are loaded AND the server is fully started.
      // Writing in onEnable() is too early — protocol handlers aren't ready yet.
      server.pluginManager.registerEvents(
          object : Listener {
            @EventHandler
            fun onServerLoad(@Suppress("UNUSED_PARAMETER") event: ServerLoadEvent) {
              val readyFlag = File(serverRoot(), ".paperplane/server-ready")
              readyFlag.parentFile.mkdirs()
              readyFlag.writeText(System.currentTimeMillis().toString())
            }
          },
          this,
      )

      logger.info("PaperPlane companion enabled")
    } catch (
        @Suppress("TooGenericExceptionCaught") // Startup involves reflection, I/O, and server API
        e: Exception) {
      logger.severe("PaperPlane companion failed to enable: ${e.message}")
      logger.severe(e.stackTraceToString())
      writeCompanionError("PaperPlane companion failed to enable: ${e.message}")
      server.pluginManager.disablePlugin(this)
    }
  }

  /**
   * Resolves the Paper server root directory. Bukkit's [dataFolder] is
   * `<serverRoot>/plugins/<plugin-name>`, so the root is two levels up.
   */
  private fun serverRoot(): File = dataFolder.absoluteFile.parentFile.parentFile

  /**
   * Writes a companion startup failure to `.paperplane/companion-error` so the CLI's `waitForReady`
   * can report it and abort promptly. Best-effort — a failed write just falls back to the timeout.
   */
  private fun writeCompanionError(message: String) {
    try {
      val errFile = File(serverRoot(), ".paperplane/companion-error")
      errFile.parentFile.mkdirs()
      errFile.writeText(message)
    } catch (
        @Suppress("TooGenericExceptionCaught") // Diagnostic write must never mask the real failure.
        e: Exception) {
      logger.warning("Failed to write companion-error flag: ${e.message}")
    }
  }

  override fun onDisable() {
    // Tear down the inner plugin BEFORE companion's own teardown — otherwise the inner's
    // onDisable never fires and players' state isn't saved on `stop`. The host is only present if a
    // load request ever built it (hot-reload mode); native modes never created one.
    if (::buildStatusBar.isInitialized) {
      buildStatusBar.hostOrNull?.let { host ->
        try {
          host.shutdown()
        } catch (
            @Suppress(
                "TooGenericExceptionCaught"
            ) // Defensive — inner teardown shouldn't take companion down
            e: Exception) {
          logger.warning("Inner plugin shutdown raised: ${e.message}")
        }
      }
      try {
        buildStatusBar.stop()
      } catch (
          @Suppress("TooGenericExceptionCaught") // Defensive — plugin may be partially initialized
          _: Exception) {}
    }
    logger.info("PaperPlane companion disabled")
  }
}

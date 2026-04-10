package dev.paperplane.companion

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
      // Patch JavaPlugin constructor for dev-mode class loading.
      // Must happen before any directory-based reloads.
      JavaPluginPatcher.patchIfNeeded()

      errorCatcher = ErrorCatcher(this)
      buildStatusBar = BuildStatusBar(this)

      server.pluginManager.registerEvents(errorCatcher, this)
      server.pluginManager.registerEvents(AutoOpListener(serverRoot()), this)
      server.pluginManager.registerEvents(SaveProtectionListener(buildStatusBar), this)
      buildStatusBar.start()

      // Signal CLI that server is ready AFTER full initialization (worlds, protocol, datapacks)
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
    }
  }

  /**
   * Resolves the Paper server root directory. Bukkit's [dataFolder] is
   * `<serverRoot>/plugins/<plugin-name>`, so the root is two levels up. Centralized here so any
   * future layout change only needs one fix.
   */
  private fun serverRoot(): File = dataFolder.absoluteFile.parentFile.parentFile

  override fun onDisable() {
    try {
      buildStatusBar.stop()
    } catch (
        @Suppress("TooGenericExceptionCaught") // Defensive — plugin may be partially initialized
        _: Exception) {}
    logger.info("PaperPlane companion disabled")
  }
}

package dev.paperplane.companion

import dev.paperplane.companion.host.InnerPluginHost
import dev.paperplane.companion.host.ReflectionProbe
import dev.paperplane.companion.host.UnsupportedPaperVersionException
import java.io.File
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.plugin.java.JavaPlugin

class CompanionPlugin : JavaPlugin() {
  private lateinit var errorCatcher: ErrorCatcher
  private lateinit var buildStatusBar: BuildStatusBar
  private lateinit var host: InnerPluginHost

  override fun onEnable() {
    try {
      // Patch JavaPlugin's constructor to allow non-PluginClassLoader instantiation. Must run
      // before the host loads any inner plugin via DevPluginClassLoader.
      JavaPluginPatcher.patchIfNeeded()

      // Probe Paper internals once at startup. Fails fast on an unsupported Paper version with a
      // clear error rather than limping along.
      val probe = ReflectionProbe.probe(server)
      host = InnerPluginHost(server, javaClass.classLoader, probe, logger)

      errorCatcher = ErrorCatcher(this)
      buildStatusBar = BuildStatusBar(this, host)

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

      logger.info("PaperPlane companion enabled (host ready)")
    } catch (e: UnsupportedPaperVersionException) {
      logger.severe(e.message)
      // Disable rather than throw — Bukkit catches throws here and prints stack noise.
      server.pluginManager.disablePlugin(this)
    } catch (e: AgentNotAvailableException) {
      logger.severe(e.message)
      server.pluginManager.disablePlugin(this)
    } catch (
        @Suppress("TooGenericExceptionCaught") // Startup involves reflection, I/O, and server API
        e: Exception) {
      logger.severe("PaperPlane companion failed to enable: ${e.message}")
      logger.severe(e.stackTraceToString())
      server.pluginManager.disablePlugin(this)
    }
  }

  /**
   * Resolves the Paper server root directory. Bukkit's [dataFolder] is
   * `<serverRoot>/plugins/<plugin-name>`, so the root is two levels up.
   */
  private fun serverRoot(): File = dataFolder.absoluteFile.parentFile.parentFile

  override fun onDisable() {
    // Tear down the inner plugin BEFORE companion's own teardown — otherwise the inner's
    // onDisable never fires and players' state isn't saved on `stop`.
    if (::host.isInitialized) {
      try {
        host.shutdown()
      } catch (
          @Suppress("TooGenericExceptionCaught") // Defensive — inner teardown shouldn't take companion down
          e: Exception) {
        logger.warning("Inner plugin shutdown raised: ${e.message}")
      }
    }
    if (::buildStatusBar.isInitialized) {
      try {
        buildStatusBar.stop()
      } catch (
          @Suppress("TooGenericExceptionCaught") // Defensive — plugin may be partially initialized
          _: Exception) {}
    }
    logger.info("PaperPlane companion disabled")
  }
}

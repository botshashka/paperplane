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
  private lateinit var messageHandler: CompanionMessageHandler
  private var socketServer: CompanionSocketServer? = null

  override fun onEnable() {
    try {
      errorCatcher = ErrorCatcher(this)
      // The socket server's callbacks fire on its reader threads; hop every message onto the
      // server main thread before it reaches the handler, whose state is main-thread-confined
      // (same discipline the old scheduler-poll loop enforced). The callbacks close over
      // [messageHandler], assigned below — safe because nothing can connect until start() runs,
      // which happens after the assignment.
      val socket =
          CompanionSocketServer(
              logger,
              onStatus = { update ->
                server.scheduler.runTask(this, Runnable { messageHandler.handleStatus(update) })
              },
              onLoadRequest = { request ->
                server.scheduler.runTask(
                    this,
                    Runnable { messageHandler.handleLoadRequest(request) },
                )
              },
              onInstantSwap = { request ->
                server.scheduler.runTask(
                    this,
                    Runnable { messageHandler.handleInstantSwap(request) },
                )
              },
          )
      // The host is built lazily on the first load request (hot-reload only). Probing Paper
      // internals here would fail-fast the whole companion on Paper versions that predate the
      // plugin-loader API — but restart/blue-green legitimately run on those and only need the
      // status broadcasts / save protection / auto-op. The probe happens inside this provider the
      // first time a load request arrives; the handler reports a probe failure as a failed report.
      // The leak-diagnostics mode rides in on the load request itself.
      messageHandler =
          CompanionMessageHandler(
              this,
              hostProvider = { leakDiagnostics ->
                val probe = ReflectionProbe.probe(server)
                InnerPluginHost(
                    server,
                    javaClass.classLoader,
                    probe,
                    logger,
                    hostPlugin = this,
                    leakDiagnostics = leakDiagnostics,
                )
              },
              ipc = socket,
          )
      socketServer = socket
      // A previous run's spliced new-class overlay is stale bytecode by now; this server's own
      // loads must never resolve against it.
      File(serverRoot(), ".paperplane/instant-overlay").deleteRecursively()
      socket.start(serverRoot())

      server.pluginManager.registerEvents(errorCatcher, this)
      server.pluginManager.registerEvents(AutoOpListener(serverRoot()), this)
      server.pluginManager.registerEvents(SaveProtectionListener(messageHandler), this)

      // Signal the CLI that the server is ready AFTER full initialization (worlds, protocol,
      // datapacks). ServerLoadEvent fires after all plugins are loaded AND the server is fully
      // started — marking ready in onEnable() would be too early (protocol handlers aren't up).
      // The readiness signal is an explicit streamed event, never inferred from the connection:
      // an established connection only proves this companion enabled.
      server.pluginManager.registerEvents(
          object : Listener {
            @EventHandler
            fun onServerLoad(@Suppress("UNUSED_PARAMETER") event: ServerLoadEvent) {
              socketServer?.markServerReady()
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
   * Writes a companion startup failure to `.paperplane/companion-error` so the CLI's dial loop can
   * report it and abort promptly. This is the one companion→CLI signal that stays a file: a
   * companion that failed to enable may never have constructed (or may be about to tear down) the
   * socket the message would otherwise travel over. Best-effort — a failed write just falls back to
   * the CLI's connect timeout.
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
    if (::messageHandler.isInitialized) {
      messageHandler.hostOrNull?.let { host ->
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
    }
    // Close the socket last so the CLI keeps its liveness signal until teardown completes; the
    // close also removes the handshake file so nothing can dial a dead port.
    try {
      socketServer?.close()
    } catch (
        @Suppress("TooGenericExceptionCaught") // Defensive — plugin may be partially initialized
        _: Exception) {}
    socketServer = null
    logger.info("PaperPlane companion disabled")
  }
}

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
      wipeInstantOverlay()
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
   * Clears the spliced new-class overlay. Run on enable AND on disable, and the enable wipe is the
   * load-bearing one: the overlay is only ever appended to a loader's URL list, so its bytes can
   * shadow nothing this incarnation defines — but a *previous* run's classes sitting there when
   * this server boots would resolve as if they were current. The disable wipe is hygiene, and it
   * cannot be relied on (a killed process never runs it), which is why enable wipes too.
   *
   * A failed delete is logged, never ignored: the overlay is stale bytecode, so silently leaving it
   * behind is the one outcome that could serve a class nobody built.
   */
  private fun wipeInstantOverlay() {
    val overlay = InstantSwapper.overlayDir(serverRoot())
    if (!overlay.exists()) return
    if (!overlay.deleteRecursively()) {
      logger.warning(
          "Could not fully clear ${overlay.path} — a previous run's instant-patch classes may " +
              "still be on the plugin classloader's search path."
      )
    }
  }

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
    wipeInstantOverlay()
    logger.info("PaperPlane companion disabled")
  }
}

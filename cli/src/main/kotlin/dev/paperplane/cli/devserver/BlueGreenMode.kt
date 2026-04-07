package dev.paperplane.cli.devserver

import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.server.ServerSync
import dev.paperplane.cli.server.VelocityDownloader
import dev.paperplane.cli.server.VelocityManager
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.ui.TerminalUI.PhaseEnd
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class BlueGreenMode(private val session: DevSession) {
  companion object {
    // Blue-green backend ports. The Velocity proxy listens on DEFAULT_PORT (25565);
    // these sit behind it. Not used in restart or hot-reload modes.
    internal const val SERVER_A_PORT = 25566
    internal const val SERVER_B_PORT = 25567
    private const val TRANSFER_SETTLE_DELAY_MS = 200L
  }

  internal enum class Slot(val serverName: String, val port: Int) {
    SERVER("server", SERVER_A_PORT),
    SWAP("swap", SERVER_B_PORT);

    fun other() = if (this == SERVER) SWAP else SERVER
  }

  private val servers =
      mapOf(
          Slot.SERVER to
              PaperServerManager(
                  File(session.ppDir, "server"),
                  session.downloader,
                  Slot.SERVER.port,
              ),
          Slot.SWAP to
              PaperServerManager(
                  File(session.ppDir, "server-swap"),
                  session.downloader,
                  Slot.SWAP.port,
              ),
      )

  private val velocityDownloader = VelocityDownloader(File(session.ppDir, "cache"))
  private val velocityManager = VelocityManager(File(session.ppDir, "proxy"))
  private var activeSlot = Slot.SERVER

  private data class RunningState(val metadata: ProjectMetadata, val paperJar: File)

  fun run() {
    val shuttingDown = AtomicBoolean(false)
    Runtime.getRuntime()
        .addShutdownHook(
            Thread {
              if (!shuttingDown.get()) {
                TerminalUI.clearPinnedFooter()
                println()
              }
              servers.values.forEach { it.stop() }
              velocityManager.stop()
              session.gradle.close()
            }
        )

    val state = runStartup(shuttingDown) ?: return

    val builtJar = File(session.projectDir, state.metadata.jarPath)
    preWarmStandby(
        servers[Slot.SWAP]!!,
        servers[activeSlot]!!,
        Slot.SWAP.port,
        builtJar,
        state.paperJar,
    )

    session.runMainWatchLoop(
        onChanged = { _ -> rebuildAndUpdateSlot(state.metadata, state.paperJar) },
        healthCheck = {
          if (!shuttingDown.get() && !velocityManager.isRunning()) {
            TerminalUI.error("Proxy process exited unexpectedly")
            false
          } else true
        },
        cleanup = {
          servers.values.forEach { it.stop() }
          velocityManager.stop()
          session.gradle.close()
        },
    )
  }

  private fun runStartup(shuttingDown: AtomicBoolean): RunningState? {
    var state: RunningState? = null
    TerminalUI.phase {
      val metadata = session.resolveMetadataOrAbort(shuttingDown) ?: return@phase PhaseEnd.None
      val active = servers[activeSlot]!!
      val paperJar =
          when (val outcome = session.initialBuild(metadata, active)) {
            is DevSession.BuildOutcome.Success -> outcome.paperJar
            is DevSession.BuildOutcome.BuildFailed -> {
              enterFixRecovery()
              return@phase PhaseEnd.Waiting
            }
          }
      if (!startProxy()) return@phase PhaseEnd.None
      if (!startInitialServer(metadata, paperJar)) return@phase PhaseEnd.None
      session.showServerInfo(
          metadata,
          "localhost:${PaperServerManager.DEFAULT_PORT} (via proxy)",
          "blue-green (zero-downtime)",
      )
      state = RunningState(metadata, paperJar)
      PhaseEnd.Watching
    }
    return state
  }

  private fun enterFixRecovery(): Nothing {
    session.runFixWatcher(
        cleanup = {
          servers.values.forEach { it.stop() }
          velocityManager.stop()
          session.gradle.close()
        },
    ) {
      when (val attempt = session.handleFixAttempt(null)) {
        is DevSession.FixAttempt.BuildFailed -> PhaseEnd.Waiting
        is DevSession.FixAttempt.Success -> {
          ensureProxyRunning()
          startFixedServer(attempt.metadata, attempt.paperJar)
        }
      }
    }
    error("fix recovery loop returned")
  }

  /**
   * Wraps [rebuild] so it can update [activeSlot] as a side effect while returning only [PhaseEnd]
   * to the watcher callback.
   */
  private fun rebuildAndUpdateSlot(metadata: ProjectMetadata, paperJar: File): PhaseEnd {
    val (newSlot, end) = rebuild(metadata, paperJar)
    activeSlot = newSlot
    return end
  }

  // ── Proxy + server startup ───────────────────────────────────────────

  private fun startProxy(): Boolean {
    velocityManager.configure(
        serverPort = Slot.SERVER.port,
        swapPort = Slot.SWAP.port,
        proxyPort = PaperServerManager.DEFAULT_PORT,
    )
    val proxyStart = System.currentTimeMillis()
    val velocityJar = TerminalUI.spin("Downloading Velocity...") { velocityDownloader.download() }
    velocityManager.start(velocityJar)
    val proxyReady =
        TerminalUI.spin("Starting Velocity proxy...") {
          velocityManager.waitForReady(PaperServerManager.DEFAULT_PORT)
        }
    val proxyDuration = session.formatDuration(System.currentTimeMillis() - proxyStart)

    return if (proxyReady) {
      TerminalUI.success("Velocity proxy ready", proxyDuration)
      true
    } else {
      TerminalUI.error("Proxy failed to start", proxyDuration)
      false
    }
  }

  private fun startInitialServer(metadata: ProjectMetadata, paperJar: File): Boolean {
    val active = servers[activeSlot]!!
    servers.values.forEach { it.cleanupStale() }
    active.configure()
    active.configureVelocityForwarding(velocityManager.forwardingSecret)

    val builtJar = File(session.projectDir, metadata.jarPath)
    active.copyPlugin(builtJar)
    active.copyCompanion()

    val mcVersion = session.resolveMcVersion(metadata)
    val serverStart = System.currentTimeMillis()
    active.start(paperJar, session.config.server.jvmArgs)
    val ready = TerminalUI.spin("Starting Paper $mcVersion server...") { active.waitForReady() }
    val serverDuration = session.formatDuration(System.currentTimeMillis() - serverStart)

    return if (ready) {
      TerminalUI.success("Paper $mcVersion server ready", serverDuration)
      active.writeCompanionStatus("ready", mapOf("duration" to serverDuration))
      true
    } else {
      TerminalUI.error("Server failed to start", serverDuration)
      active.writeCompanionStatus("error", mapOf("message" to "Server failed to start"))
      false
    }
  }

  // ── Rebuild ──────────────────────────────────────────────────────────

  private fun rebuild(metadata: ProjectMetadata, paperJar: File): Pair<Slot, PhaseEnd> {
    val totalStart = System.currentTimeMillis()
    val active = servers[activeSlot]!!
    val standbySlot = activeSlot.other()
    val standby = servers[standbySlot]!!
    val builtJar = File(session.projectDir, metadata.jarPath)

    if (!buildAndSync(active, standby, standbySlot, builtJar)) {
      return activeSlot to PhaseEnd.Waiting
    }
    if (!deployAndTransfer(standby, standbySlot, active, builtJar, paperJar, totalStart)) {
      return activeSlot to PhaseEnd.Waiting
    }

    // Pre-warm old active as next standby
    Thread(
            {
              active.stop()
              preWarmStandby(active, standby, activeSlot.port, builtJar, paperJar)
            },
            "stop-and-prewarm-${activeSlot.serverName}",
        )
        .apply { isDaemon = true }
        .start()

    return standbySlot to PhaseEnd.Watching
  }

  private fun buildAndSync(
      active: PaperServerManager,
      standby: PaperServerManager,
      standbySlot: Slot,
      builtJar: File,
  ): Boolean {
    active.writeCompanionStatus("saving")
    TerminalUI.spin("Saving world...") { active.waitForSave() }

    if (standby.isRunning()) standby.stop()

    active.writeCompanionStatus("building")
    val buildStart = System.currentTimeMillis()

    val syncThread =
        Thread(
            {
              ServerSync.syncServerState(
                  active.serverDir,
                  standby.serverDir,
                  standbySlot.port,
                  builtJar.name,
              )
            },
            "sync-to-${standbySlot.serverName}",
        )
    syncThread.start()

    val buildSuccess = session.gradle.build()
    val buildDuration = session.formatDuration(System.currentTimeMillis() - buildStart)
    syncThread.join()

    if (!buildSuccess) {
      TerminalUI.error("Build failed", buildDuration)
      active.writeCompanionStatus("error", mapOf("message" to "Build failed"))
      return false
    }
    TerminalUI.success("Build succeeded", buildDuration)
    return true
  }

  private fun deployAndTransfer(
      standby: PaperServerManager,
      standbySlot: Slot,
      active: PaperServerManager,
      builtJar: File,
      paperJar: File,
      totalStart: Long,
  ): Boolean {
    standby.copyPlugin(builtJar)
    standby.copyCompanion()

    val serverStart = System.currentTimeMillis()
    standby.start(paperJar, session.config.server.jvmArgs)
    val ready =
        TerminalUI.spin("Starting ${standbySlot.serverName} server...") { standby.waitForReady() }
    val serverDuration = session.formatDuration(System.currentTimeMillis() - serverStart)

    if (!ready) {
      TerminalUI.error("Standby server failed to start", serverDuration)
      standby.stop()
      active.writeCompanionStatus("error", mapOf("message" to "Standby failed to start"))
      return false
    }

    velocityManager.clearTransferComplete()
    velocityManager.writeActiveServer(standbySlot.serverName, transfer = true)
    velocityManager.waitForTransferComplete()
    Thread.sleep(TRANSFER_SETTLE_DELAY_MS)

    val totalDuration = session.formatDuration(System.currentTimeMillis() - totalStart)
    standby.writeCompanionStatus("ready", mapOf("duration" to totalDuration))

    TerminalUI.success("Server ready (${standbySlot.serverName})", serverDuration)
    TerminalUI.totalTime(totalDuration)
    return true
  }

  private fun preWarmStandby(
      standby: PaperServerManager,
      source: PaperServerManager,
      standbyPort: Int,
      pluginJar: File,
      paperJar: File,
  ) {
    try {
      if (standby.isRunning()) return
      standby.serverDir.mkdirs()
      standby.cleanupStale()
      standby.configure()
      standby.configureVelocityForwarding(velocityManager.forwardingSecret)
      ServerSync.syncServerState(source.serverDir, standby.serverDir, standbyPort, pluginJar.name)
      standby.copyPlugin(pluginJar)
      standby.copyCompanion()
      standby.start(paperJar, session.config.server.jvmArgs)
      standby.waitForReady()
    } catch (_: Exception) {
      // Pre-warm is best-effort; failure here doesn't affect the active server
    }
  }

  // ── Wait-for-fix helpers ────────────────────────────────────────────

  private fun ensureProxyRunning() {
    if (velocityManager.isRunning()) return
    val velocityJar = velocityDownloader.download()
    velocityManager.configure(
        serverPort = Slot.SERVER.port,
        swapPort = Slot.SWAP.port,
        proxyPort = PaperServerManager.DEFAULT_PORT,
    )
    velocityManager.start(velocityJar)
    velocityManager.waitForReady(PaperServerManager.DEFAULT_PORT)
  }

  private fun startFixedServer(metadata: ProjectMetadata, paperJar: File): PhaseEnd {
    val blue = servers[Slot.SERVER]!!
    blue.cleanupStale()
    blue.configure()
    blue.configureVelocityForwarding(velocityManager.forwardingSecret)
    val builtJar = File(session.projectDir, metadata.jarPath)
    blue.copyPlugin(builtJar)
    blue.copyCompanion()

    val serverStart = System.currentTimeMillis()
    blue.start(paperJar, session.config.server.jvmArgs)
    val ready = blue.waitForReady()
    val serverDuration = session.formatDuration(System.currentTimeMillis() - serverStart)
    return if (ready) {
      TerminalUI.success("Server ready", serverDuration)
      blue.writeCompanionStatus("ready", mapOf("duration" to serverDuration))
      velocityManager.writeActiveServer("server")
      PhaseEnd.Watching
    } else {
      TerminalUI.error("Server failed to start", serverDuration)
      PhaseEnd.Waiting
    }
  }
}

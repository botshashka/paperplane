package dev.paperplane.cli.devserver

import dev.paperplane.cli.devserver.DevSession.RunningState
import dev.paperplane.cli.devserver.DevSession.StartupOutcome
import dev.paperplane.cli.devserver.instant.BaselineTracker
import dev.paperplane.cli.devserver.instant.InstantLane
import dev.paperplane.cli.gradle.MetadataResult
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.ipc.CompanionWire
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.server.ServerSync
import dev.paperplane.cli.server.VelocityDownloader
import dev.paperplane.cli.server.VelocityManager
import dev.paperplane.cli.ui.TerminalUI.PhaseEnd
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal open class BlueGreenMode(
    private val session: DevSession,
    private val servers: Map<Slot, PaperServerManager> =
        mapOf(
            Slot.SERVER to
                PaperServerManager(
                    File(session.ppDir, "server"),
                    session.downloader,
                    session.ui,
                    Slot.SERVER.port,
                    protocolLog = session.config.dev.protocolLog,
                ),
            Slot.SWAP to
                PaperServerManager(
                    File(session.ppDir, "server-swap"),
                    session.downloader,
                    session.ui,
                    Slot.SWAP.port,
                    protocolLog = session.config.dev.protocolLog,
                ),
        ),
    private val velocityDownloader: VelocityDownloader =
        VelocityDownloader(File(session.ppDir, "cache")),
    private val velocityManager: VelocityManager =
        VelocityManager(File(session.ppDir, "proxy"), session.ui),
) {
  companion object {
    // Blue-green backend ports. The Velocity proxy listens on DEFAULT_PORT (25565);
    // these sit behind it. Not used in restart or hot-reload modes.
    internal const val SERVER_A_PORT = 25566
    internal const val SERVER_B_PORT = 25567
    private const val TRANSFER_SETTLE_DELAY_MS = 200L
    private const val PREWARM_JOIN_TIMEOUT_MS = 2000L
  }

  internal enum class Slot(val serverName: String, val port: Int) {
    SERVER("server", SERVER_A_PORT),
    SWAP("swap", SERVER_B_PORT);

    fun other() = if (this == SERVER) SWAP else SERVER
  }

  private var activeSlot = Slot.SERVER

  // The instant fast lane, and one confirmed-loaded baseline per slot: each backend JVM runs its
  // own plugin incarnation, so what is "confirmed loaded" is a per-slot fact.
  internal val lane = InstantLane(session)
  internal val baselines = mapOf(Slot.SERVER to BaselineTracker(), Slot.SWAP to BaselineTracker())

  /**
   * Post-swap pre-warm runs in the background so the main loop can return to "Watching" quickly. If
   * another rebuild triggers before this finishes, we must wait for it — otherwise rebuild's
   * `standby.stop()` can race with the pre-warm's `standby.start()` and leave the port in a
   * half-bound state that crashes the next boot with "Address already in use".
   */
  @Volatile private var preWarmThread: Thread? = null

  private val shuttingDown = AtomicBoolean(false)
  private val shutdownDone = AtomicBoolean(false)

  /**
   * Idempotent shutdown: joins any in-flight pre-warm, stops both backends in parallel, then the
   * proxy and gradle. Called from both the JVM shutdown hook and the main loop's cleanup lambda —
   * whichever runs first wins; the second call is a no-op.
   */
  private fun shutdownAll() {
    if (!shutdownDone.compareAndSet(false, true)) return
    shuttingDown.set(true)
    // Let any in-flight post-swap pre-warm finish its current step before we tear down; a short
    // cap keeps Ctrl+C responsive even if the background thread is stuck.
    try {
      preWarmThread?.join(PREWARM_JOIN_TIMEOUT_MS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    preWarmThread = null

    // Stop both backends in parallel so shutdown doesn't wait 2 × graceful-stop-timeout.
    val stopThreads =
        servers.values.map { mgr ->
          Thread({ mgr.stop() }, "shutdown-stop-${mgr.serverDir.name}").apply { start() }
        }
    stopThreads.forEach { it.join() }
    // Sync auto-opped players back to paperplane.yml from whichever server is currently active.
    // Must happen after stop() (so ops.json is fully flushed) but before proxy teardown.
    servers[activeSlot]?.let { session.syncOpsBackToConfig(it) }
    velocityManager.stop()
    session.gradle.close()
  }

  fun run() {
    Runtime.getRuntime()
        .addShutdownHook(
            Thread {
              if (!shuttingDown.get()) {
                session.ui.clearPinnedFooter()
              }
              shutdownAll()
            }
        )

    val state: RunningState =
        when (val outcome = runStartup()) {
          is StartupOutcome.Running -> outcome.state
          StartupOutcome.BuildFailed,
          StartupOutcome.LoadFailed -> enterFixRecovery() ?: return
          StartupOutcome.Aborted -> return
        }

    val builtJar = File(session.projectDir, state.metadata.jarPath)
    preWarmStandby(
        servers[Slot.SWAP]!!,
        servers[activeSlot]!!,
        builtJar,
        state.paperJar,
    )

    session.runMainWatchLoop(
        onChanged = { _ -> rebuildAndUpdateSlot(state.metadata, state.paperJar) },
        onForceSwap = {
          rebuildAndUpdateSlot(state.metadata, state.paperJar, forceFullSwap = true)
        },
        healthCheck = {
          if (!shuttingDown.get() && !velocityManager.isRunning()) {
            session.ui.error("Proxy process exited unexpectedly")
            false
          } else true
        },
        cleanup = { shutdownAll() },
    )
  }

  internal fun runStartup(): StartupOutcome {
    var outcome: StartupOutcome = StartupOutcome.Aborted
    session.ui.phase {
      val metadata =
          when (val res = session.resolveMetadata()) {
            is MetadataResult.Success -> res.metadata
            MetadataResult.PluginNotApplied -> return@phase PhaseEnd.None
            MetadataResult.TaskFailed -> {
              outcome = StartupOutcome.BuildFailed
              return@phase PhaseEnd.Waiting
            }
          }
      val active = servers[activeSlot]!!
      val paperJar =
          when (val result = session.initialBuild(metadata, active)) {
            is DevSession.BuildOutcome.Success -> result.paperJar
            is DevSession.BuildOutcome.BuildFailed -> {
              outcome = StartupOutcome.BuildFailed
              return@phase PhaseEnd.Waiting
            }
          }
      if (!startProxy()) return@phase PhaseEnd.None
      val state =
          when (val result = startInitialServer(metadata, paperJar)) {
            is DevSession.ServerStartResult.Running -> result.state
            DevSession.ServerStartResult.LoadFailed -> {
              outcome = StartupOutcome.LoadFailed
              return@phase PhaseEnd.Waiting
            }
            DevSession.ServerStartResult.Aborted -> return@phase PhaseEnd.None
          }
      lane.confirmFullSwap(baselines[activeSlot]!!)
      session.showServerInfo(
          metadata,
          "localhost:${PaperServerManager.DEFAULT_PORT} (via proxy)",
          "blue-green (zero-downtime)",
          instantLabel = lane.capabilityLabel(servers[activeSlot]!!, metadata),
      )
      outcome = StartupOutcome.Running(state)
      PhaseEnd.Watching
    }
    return outcome
  }

  /**
   * Blocks on the fix-recovery file watcher. Returns the recovered [RunningState] on a successful
   * post-failure build + server start, or null on Ctrl+C. Test overrides may return a scripted
   * state to bypass the real file watcher.
   */
  protected open fun enterFixRecovery(): RunningState? =
      session.runFixRecoveryAndWait(
          onShutdown = { shutdownAll() },
      ) { _ ->
        when (val attempt = session.handleFixAttempt(null)) {
          is DevSession.FixAttempt.BuildFailed -> null
          is DevSession.FixAttempt.Success -> {
            ensureProxyRunning()
            startFixedServer(attempt.metadata, attempt.paperJar)
          }
        }
      }

  /**
   * Wraps [rebuild] so it can update [activeSlot] as a side effect while returning only [PhaseEnd]
   * to the watcher callback.
   */
  private fun rebuildAndUpdateSlot(
      metadata: ProjectMetadata,
      paperJar: File,
      forceFullSwap: Boolean = false,
  ): PhaseEnd {
    val (newSlot, end) = rebuild(metadata, paperJar, forceFullSwap)
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
    val velocityJar = session.ui.spin("Downloading Velocity...") { velocityDownloader.download() }
    velocityManager.start(velocityJar)
    val proxyReady =
        session.ui.spin("Starting Velocity proxy...") {
          velocityManager.waitForReady(PaperServerManager.DEFAULT_PORT)
        }
    val proxyDuration = session.formatDuration(System.currentTimeMillis() - proxyStart)

    return if (proxyReady) {
      session.ui.success("Velocity proxy ready", proxyDuration)
      true
    } else {
      session.ui.error("Proxy failed to start", proxyDuration)
      false
    }
  }

  private fun startInitialServer(
      metadata: ProjectMetadata,
      paperJar: File,
  ): DevSession.ServerStartResult {
    val active = servers[activeSlot]!!
    // Clean stale lock files on BOTH slots before binding — the standby may have leftover state
    // from a previous crash, and we don't want it holding its port when pre-warm runs.
    servers.values.forEach { it.cleanupStale() }
    return session.startServerAndReport(
        serverManager = active,
        metadata = metadata,
        paperJar = paperJar,
        extraConfigure = { it.configureVelocityForwarding(velocityManager.forwardingSecret) },
    )
  }

  // ── Rebuild ──────────────────────────────────────────────────────────

  /**
   * Internal for tests: the deploy/transfer/pre-warm cycle is driven directly. The instant lane
   * runs first, against the live active backend and BEFORE any world save or standby teardown — a
   * patched cycle never touches the standby at all (its stale jar is benign: every fall-through
   * swap re-deploys before transferring). [forceFullSwap] skips the lane (manual escape hatch).
   */
  internal fun rebuild(
      metadata: ProjectMetadata,
      paperJar: File,
      forceFullSwap: Boolean = false,
  ): Pair<Slot, PhaseEnd> {
    val totalStart = System.currentTimeMillis()
    val active = servers[activeSlot]!!
    val standbySlot = activeSlot.other()
    val standby = servers[standbySlot]!!
    val builtJar = File(session.projectDir, metadata.jarPath)

    if (!forceFullSwap) {
      lane.runOrEscalate(active, metadata, baselines[activeSlot]!!, totalStart, "full swap")?.let {
        return activeSlot to it
      }
    }

    if (!buildAndSync(active, standby, standbySlot, builtJar)) {
      return activeSlot to PhaseEnd.Waiting
    }
    if (!deployAndTransfer(standby, standbySlot, active, builtJar, paperJar, totalStart)) {
      return activeSlot to PhaseEnd.Waiting
    }
    // The promoted standby is verifiably running this build; the retiring active's baseline is
    // stale until its pre-warm replacement confirms on a future swap.
    lane.confirmFullSwap(baselines[standbySlot]!!)
    baselines[activeSlot]!!.reset()

    // Pre-warm old active as next standby
    preWarmThread =
        Thread(
                {
                  active.stop()
                  preWarmStandby(active, standby, builtJar, paperJar)
                },
                "stop-and-prewarm-${activeSlot.serverName}",
            )
            .apply {
              isDaemon = true
              start()
            }

    return standbySlot to PhaseEnd.Watching
  }

  /** Internal for tests: blocks until the post-swap pre-warm finishes. */
  internal fun awaitPreWarmForTest() {
    preWarmThread?.join()
  }

  private fun buildAndSync(
      active: PaperServerManager,
      standby: PaperServerManager,
      standbySlot: Slot,
      builtJar: File,
  ): Boolean {
    // Wait for any in-flight post-swap pre-warm to finish before touching the standby — see
    // preWarmThread.
    preWarmThread?.join()
    preWarmThread = null

    // saveWorld sends the `saving` status (which also opens the companion's save-protection
    // window) and awaits the saveComplete event.
    session.ui.spin("Saving world...") { active.saveWorld() }

    if (standby.isRunning()) standby.stop()

    active.sendCompanionStatus(CompanionWire.STATE_BUILDING)
    val buildStart = System.currentTimeMillis()

    val syncThread =
        Thread(
            { ServerSync.syncServerState(active.serverDir, standby.serverDir, builtJar.name) },
            "sync-to-${standbySlot.serverName}",
        )
    syncThread.start()

    val buildSuccess = session.gradle.build()
    val buildDuration = session.formatDuration(System.currentTimeMillis() - buildStart)
    syncThread.join()

    if (!buildSuccess) {
      session.ui.error("Build failed", buildDuration)
      active.sendCompanionStatus(CompanionWire.STATE_ERROR, message = "Build failed")
      return false
    }
    session.ui.success("Build succeeded", buildDuration)
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
    standby.copyPluginToPluginsDir(builtJar)
    standby.copyCompanion()

    // The standby is about to become the active server — surface its logs.
    standby.logSuppressed = false
    active.logSuppressed = true

    // Safety net: ensure nothing is holding the port before we bind. stop() should have already
    // released it, but this guards against slow kernel unbind and leftover processes.
    standby.cleanupStale()

    val serverStart = System.currentTimeMillis()
    standby.start(paperJar, session.launchSpec)
    val ready =
        session.ui.spin("Starting ${standbySlot.serverName} server...") { standby.waitForReady() }
    val serverDuration = session.formatDuration(System.currentTimeMillis() - serverStart)

    if (!ready) {
      session.ui.error("Standby server failed to start", serverDuration)
      standby.stop()
      active.sendCompanionStatus(CompanionWire.STATE_ERROR, message = "Standby failed to start")
      return false
    }

    velocityManager.clearTransferComplete()
    velocityManager.writeActiveServer(standbySlot.serverName, transfer = true)
    velocityManager.waitForTransferComplete()
    Thread.sleep(TRANSFER_SETTLE_DELAY_MS)

    val totalDuration = session.formatDuration(System.currentTimeMillis() - totalStart)
    standby.sendCompanionStatus(CompanionWire.STATE_READY, duration = totalDuration)

    session.ui.success("Server ready (${standbySlot.serverName})", serverDuration)
    session.ui.totalTime(totalDuration)
    return true
  }

  private fun preWarmStandby(
      standby: PaperServerManager,
      source: PaperServerManager,
      pluginJar: File,
      paperJar: File,
  ) {
    try {
      if (standby.isRunning()) return
      standby.serverDir.mkdirs()
      standby.cleanupStale()
      standby.configure(session.config.server)
      standby.configureVelocityForwarding(velocityManager.forwardingSecret)
      ServerSync.syncServerState(source.serverDir, standby.serverDir, pluginJar.name)
      standby.copyPluginToPluginsDir(pluginJar)
      standby.copyCompanion()
      // Pre-warmed standby runs silently — its logs would interleave with the active server's.
      standby.logSuppressed = true
      standby.start(paperJar, session.launchSpec)
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

  internal fun startFixedServer(metadata: ProjectMetadata, paperJar: File): RunningState? {
    val blue = servers[Slot.SERVER]!!
    blue.cleanupStale()
    blue.configure(session.config.server)
    blue.configureVelocityForwarding(velocityManager.forwardingSecret)
    val builtJar = File(session.projectDir, metadata.jarPath)
    // Native deploy into plugins/ — staging here would boot the recovered server WITHOUT the
    // user's plugin while still reporting "Server ready".
    blue.copyPluginToPluginsDir(builtJar)
    blue.copyCompanion()

    val serverStart = System.currentTimeMillis()
    blue.start(paperJar, session.launchSpec)
    val ready = blue.waitForReady()
    val serverDuration = session.formatDuration(System.currentTimeMillis() - serverStart)
    return if (ready) {
      session.ui.success("Server ready", serverDuration)
      blue.sendCompanionStatus(CompanionWire.STATE_READY, duration = serverDuration)
      velocityManager.writeActiveServer("server")
      lane.confirmFullSwap(baselines[Slot.SERVER]!!)
      RunningState(metadata, paperJar)
    } else {
      session.ui.error("Server failed to start", serverDuration)
      null
    }
  }
}

package dev.paperplane.cli.devserver

import dev.paperplane.cli.devserver.DevSession.RunningState
import dev.paperplane.cli.devserver.DevSession.StartupOutcome
import dev.paperplane.cli.devserver.instant.BaselineTracker
import dev.paperplane.cli.devserver.instant.InstantLane
import dev.paperplane.cli.gradle.MetadataResult
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.ipc.CompanionWire
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.ui.TerminalUI.PhaseEnd
import java.io.File

internal open class HotReloadMode(
    private val session: DevSession,
    private val serverManager: PaperServerManager =
        PaperServerManager(
            File(session.ppDir, "server"),
            session.downloader,
            session.ui,
            protocolLog = session.config.dev.protocolLog,
        ),
) {
  internal companion object {
    private const val RELOAD_TIMEOUT_MS = 10_000L

    /**
     * Pure helper: build a [LoadRequest] from a rebuild's inputs. Extracted so the strategy
     * selection (DIRECTORY vs JAR) can be tested without standing up a full HotReloadMode.
     *
     * Strategy selection rules:
     * - No fastMeta or no classesDir → JAR fallback (empty classesDirs).
     * - Have fastMeta → DIRECTORY (classesDirs populated).
     */
    internal fun buildLoadRequest(
        metadata: ProjectMetadata,
        fastMeta: ProjectMetadata?,
        stagedJarPath: String,
        leakDiagnostics: String = "summary",
    ): LoadRequest {
      val classesDirs: List<String>
      val resourcesDir: String
      val runtimeClasspath: List<String>

      if (fastMeta != null && fastMeta.classesDir.isNotEmpty()) {
        classesDirs = fastMeta.effectiveClassesDirs
        resourcesDir = fastMeta.resourcesDir
        runtimeClasspath = fastMeta.runtimeClasspath
      } else {
        classesDirs = emptyList()
        resourcesDir = ""
        runtimeClasspath = emptyList()
      }

      return LoadRequest(
          requestId = newRequestId(),
          jarPath = stagedJarPath,
          pluginName = metadata.pluginName,
          classesDirs = classesDirs,
          resourcesDir = resourcesDir,
          runtimeClasspath = runtimeClasspath,
          leakDiagnostics = leakDiagnostics,
      )
    }

    /**
     * Whether [triggerReload] must (re)build the plugin JAR before staging it.
     *
     * The staged JAR is load-bearing ONLY in JAR-fallback mode — no fast-metadata classes dir,
     * which is the one case [buildLoadRequest] emits empty `classesDirs` and the companion's
     * `buildClassLoaderUrls` actually loads the JAR. A rebuild ran `classes` (compileOnly), never
     * `jar`, so in that mode the artifact at `metadata.jarPath` would otherwise be the stale
     * first-build JAR and the reload would silently run old code. In directory/hotswap mode the JAR
     * is never added to the loader, so the extra `jar` build is skipped to keep reloads fast. Also
     * rebuild when the JAR is simply missing (first reload before any `jar` task has run).
     */
    internal fun needsJarBuild(fastMeta: ProjectMetadata?, builtJarExists: Boolean): Boolean =
        !builtJarExists || fastMeta == null || fastMeta.classesDir.isEmpty()
  }

  internal val lane = InstantLane(session)
  internal val baseline = BaselineTracker()

  // The live server state, set once startup succeeds and refreshed on each leak-restart. Held in a
  // field (not just a run() local) so waitAndReport can restart the server with the same paperJar.
  // Internal so tests can seed it before driving waitAndReport directly.
  internal var runningState: RunningState? = null

  // Leak-triggered restarts this session — after the second one we nudge toward blue-green mode.
  private var leakRestartCount = 0

  // Set when a leak-restart fails to bring the server back up: the server is then deliberately
  // stopped, so the main loop's health check must not read that as a crash. The next successful
  // rebuild cold-starts the server and clears this. Internal so tests can seed/read it.
  internal var serverDownAwaitingFix = false

  fun run() {
    Runtime.getRuntime()
        .addShutdownHook(
            Thread {
              session.ui.clearPinnedFooter()
              serverManager.stop()
              session.syncOpsBackToConfig(serverManager)
              session.gradle.close()
            }
        )

    runningState =
        when (val outcome = runStartup()) {
          is StartupOutcome.Running -> outcome.state
          StartupOutcome.BuildFailed,
          StartupOutcome.LoadFailed -> enterFixRecovery() ?: return
          StartupOutcome.Aborted -> return
        }

    session.runMainWatchLoop(
        onChanged = { _ -> rebuild(runningState!!.metadata) },
        healthCheck = { healthCheck() },
        cleanup = {
          serverManager.stop()
          session.syncOpsBackToConfig(serverManager)
          session.gradle.close()
        },
        onForceSwap = { rebuild(runningState!!.metadata, forceFullSwap = true) },
    )
  }

  /**
   * Main-loop health check. A stopped server normally means the process died and the loop exits —
   * except when [serverDownAwaitingFix] is set, in which case a failed leak-restart stopped the
   * server on purpose. Report healthy then and keep watching; the next successful rebuild brings it
   * back up. Internal so tests can drive it directly.
   */
  internal fun healthCheck(): Boolean {
    if (serverDownAwaitingFix) return true
    // hasExitedUnexpectedly, not !isRunning(): the leak-limit restart stops and restarts the
    // server inside the watcher callback while this check polls concurrently from the main loop —
    // an intentional restart must not read as a crash.
    if (serverManager.hasExitedUnexpectedly()) {
      session.ui.error("Server process exited unexpectedly")
      return false
    }
    return true
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
      val paperJar =
          when (val result = session.initialBuild(metadata, serverManager)) {
            is DevSession.BuildOutcome.Success -> result.paperJar
            is DevSession.BuildOutcome.BuildFailed -> {
              outcome = StartupOutcome.BuildFailed
              return@phase PhaseEnd.Waiting
            }
          }
      val state =
          when (
              val result =
                  session.startServerAndReport(
                      serverManager = serverManager,
                      metadata = metadata,
                      paperJar = paperJar,
                      hotReload = true,
                  )
          ) {
            is DevSession.ServerStartResult.Running -> result.state
            DevSession.ServerStartResult.LoadFailed -> {
              outcome = StartupOutcome.LoadFailed
              return@phase PhaseEnd.Waiting
            }
            DevSession.ServerStartResult.Aborted -> return@phase PhaseEnd.None
          }
      lane.confirmFullSwap(baseline)
      session.showServerInfo(
          metadata,
          "localhost:${PaperServerManager.DEFAULT_PORT}",
          if (session.launchSpec.isJbr) "hot-reload (enhanced — JBR)" else "hot-reload",
          instant = lane.bannerState(serverManager),
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
          onShutdown = {
            serverManager.stop()
            session.gradle.close()
          },
      ) { _ ->
        when (val attempt = session.handleFixAttempt(serverManager)) {
          is DevSession.FixAttempt.BuildFailed -> null
          is DevSession.FixAttempt.Success -> startAfterFix(attempt)
        }
      }

  /**
   * Starts the server after a successful fix-recovery rebuild. The JVM launch identity (agent/JBR
   * wiring) comes from the session-wide [DevSession.launchSpec] like every other start; what this
   * call must still mirror from [runStartup] is `hotReload = true` — the staged-deploy strategy —
   * so the recovered server host-loads the plugin instead of leaving a stale jar in `plugins/`.
   * Returns null on a still-failing load so the fix-recovery loop keeps waiting for the next edit.
   */
  internal fun startAfterFix(attempt: DevSession.FixAttempt.Success): RunningState? =
      session
          .startServerAndReport(
              serverManager = serverManager,
              metadata = attempt.metadata,
              paperJar = attempt.paperJar,
              hotReload = true,
          )
          .stateOrNull
          ?.also { lane.confirmFullSwap(baseline) }

  // ── Rebuild ──────────────────────────────────────────────────────────

  /**
   * Internal for tests: the awaiting-fix cold-start branch is driven directly. The instant lane
   * runs first (compile + classify + in-place patch when admissible); anything it can't vouch for
   * falls through to the full host reload. [forceFullSwap] is the manual escape hatch — skip the
   * lane and reload the current build, resetting to ground truth.
   */
  internal fun rebuild(metadata: ProjectMetadata, forceFullSwap: Boolean = false): PhaseEnd {
    val totalStart = System.currentTimeMillis()

    laneSkippingRebuild(metadata, totalStart, forceFullSwap)?.let {
      return it
    }
    lane.runOrEscalate(serverManager, metadata, baseline, totalStart, "full reload")?.let {
      return it
    }
    return fullReload(metadata, totalStart)
  }

  /**
   * The two paths that bypass the lane, or null when the lane should run. A failed leak-restart
   * left the server deliberately down — there's no live server to patch or to answer a reload
   * request, so compile and cold-start instead; this successful build is the fix that lets it come
   * back up. [forceFullSwap] is the manual escape hatch.
   */
  private fun laneSkippingRebuild(
      metadata: ProjectMetadata,
      totalStart: Long,
      forceFullSwap: Boolean,
  ): PhaseEnd? {
    if (!serverDownAwaitingFix && !forceFullSwap) return null
    if (!lane.compile(serverManager)) return PhaseEnd.Waiting
    return if (serverDownAwaitingFix) restartAfterAwaitingFix(metadata)
    else fullReload(metadata, totalStart)
  }

  private fun fullReload(metadata: ProjectMetadata, totalStart: Long): PhaseEnd {
    val requestId = triggerReload(metadata)
    return waitAndReport(metadata, totalStart, requestId)
  }

  /**
   * Sends the reload request over the companion socket and returns its requestId so [waitAndReport]
   * can match the result. Internal so tests can drive the JAR-(re)build decision directly.
   */
  internal fun triggerReload(metadata: ProjectMetadata): String {
    // Restage the user JAR. It is load-bearing only in JAR-fallback mode, and the rebuild ran
    // `classes`, not `jar`, so (re)build the artifact whenever the host will actually load it —
    // otherwise a stale first-build JAR is staged and the reload silently runs old code. See
    // [needsJarBuild].
    val fastMeta = session.fastMetadata()
    val builtJar = File(session.projectDir, metadata.jarPath)
    if (needsJarBuild(fastMeta, builtJar.exists())) session.gradle.build()
    val stagedJarPath = serverManager.stagePlugin(builtJar)

    val request =
        buildLoadRequest(
            metadata,
            fastMeta,
            stagedJarPath,
            session.leakDiagnosticsWireValue(),
        )
    if (request.classesDirs.isEmpty()) session.ui.info("Strategy:", "jar (fallback)")
    else session.ui.info("Strategy:", "directory reload")
    serverManager.ipc.sendLoadRequest(request)
    return request.requestId
  }

  /** Internal for tests: the reload-result rendering branches are driven directly. */
  internal fun waitAndReport(
      metadata: ProjectMetadata,
      totalStart: Long,
      requestId: String,
  ): PhaseEnd {
    val reloadStart = System.currentTimeMillis()
    val result =
        session.ui.spin("Reloading ${metadata.pluginName}...") {
          session.loadResultWaiter.await(serverManager, requestId, RELOAD_TIMEOUT_MS)
        }
    val reloadDuration = session.formatDuration(System.currentTimeMillis() - reloadStart)

    val phaseEnd =
        when (result) {
          is LoadWaitResult.Ok -> {
            session.ui.renderLeakWarnings(result.report)
            val totalDuration = session.formatDuration(System.currentTimeMillis() - totalStart)
            session.ui.success("Plugin reloaded", reloadDuration)
            session.ui.totalTime(totalDuration)
            serverManager.ipc.sendStatus(CompanionWire.STATE_READY, duration = totalDuration)
            lane.confirmFullSwap(baseline)
            PhaseEnd.Watching
          }
          is LoadWaitResult.Failed -> {
            session.ui.error("Reload failed: ${result.message}", reloadDuration)
            serverManager.ipc.sendStatus(
                CompanionWire.STATE_ERROR,
                message = "Hot-reload failed",
            )
            PhaseEnd.Watching
          }
          LoadWaitResult.TimedOut -> {
            session.ui.error(
                "Hot-reload failed (server still running with old plugin)",
                reloadDuration,
            )
            serverManager.ipc.sendStatus(
                CompanionWire.STATE_ERROR,
                message = "Hot-reload failed",
            )
            PhaseEnd.Watching
          }
          LoadWaitResult.ServerExited -> {
            session.ui.error("Server process exited during reload", reloadDuration)
            PhaseEnd.Waiting
          }
        }

    // Leaks accumulated past the host's limit: the signal rides out on the report's
    // `action` — on the tripping Ok (this reload succeeded but leaks piled up) or,
    // belt-and-braces, on the refusal Failed sent if a prior tripping Ok was missed.
    // Either way, restart the server to reclaim the leaked memory. Runs inline in the
    // serialized watcher callback, so no concurrent rebuild can interleave.
    return if (reportAction(result) == LoadReport.ACTION_RESTART) restartForLeaks(metadata)
    else phaseEnd
  }

  /** The host-attached `action` on the result's report, if any (Ok and Failed both carry one). */
  private fun reportAction(result: LoadWaitResult): String? =
      when (result) {
        is LoadWaitResult.Ok -> result.report?.action
        is LoadWaitResult.Failed -> result.report?.action
        LoadWaitResult.TimedOut,
        LoadWaitResult.ServerExited -> null
      }

  /**
   * Restarts the server to reclaim leaked classloader memory, then keeps the hot-reload loop alive.
   * Mirrors [startAfterFix]'s startup args so the restarted server keeps the agent/JBR wiring. If
   * the restart itself fails to come back up the server is left stopped and [serverDownAwaitingFix]
   * is latched: the main loop keeps watching (its health check treats the deliberate downtime as
   * healthy) and the next successful rebuild cold-starts the server. It does NOT exit `ppl dev`.
   */
  private fun restartForLeaks(metadata: ProjectMetadata): PhaseEnd {
    leakRestartCount++
    session.ui.change(
        "Reloads leaked memory past the limit — restarting the server to reclaim it " +
            "(hot-reload continues after restart)"
    )
    if (leakRestartCount >= 2) {
      session.ui.status(
          "Leaks recur — consider `dev.mode: blue-green` in paperplane.yml for zero-downtime rebuilds."
      )
    }
    serverManager.stop()
    baseline.reset()
    return when (val restarted = startHotReloadServer(metadata)) {
      is DevSession.ServerStartResult.Running -> {
        runningState = restarted.state
        lane.confirmFullSwap(baseline)
        PhaseEnd.Watching
      }
      DevSession.ServerStartResult.LoadFailed,
      DevSession.ServerStartResult.Aborted -> {
        serverDownAwaitingFix = true
        PhaseEnd.Waiting
      }
    }
  }

  /**
   * Cold-starts the server after a fresh build clears a failed leak-restart. Mirrors
   * [restartForLeaks]'s start so the recovered server keeps the agent/JBR wiring. On success the
   * [serverDownAwaitingFix] latch is cleared and normal reloads resume; a still-failing start keeps
   * the latch so the next edit retries.
   */
  private fun restartAfterAwaitingFix(metadata: ProjectMetadata): PhaseEnd =
      when (val restarted = startHotReloadServer(metadata)) {
        is DevSession.ServerStartResult.Running -> {
          serverDownAwaitingFix = false
          runningState = restarted.state
          lane.confirmFullSwap(baseline)
          PhaseEnd.Watching
        }
        DevSession.ServerStartResult.LoadFailed,
        DevSession.ServerStartResult.Aborted -> PhaseEnd.Waiting
      }

  /**
   * Starts (or restarts) the server with the same staged-deploy wiring initial startup uses
   * (`hotReload = true`), reusing the last good [runningState]'s paperJar. The JVM launch identity
   * comes from the session-wide [DevSession.launchSpec]. Shared by the leak-restart and
   * awaiting-fix cold-start paths so neither can silently deploy differently than startup did.
   */
  private fun startHotReloadServer(metadata: ProjectMetadata): DevSession.ServerStartResult =
      session.startServerAndReport(
          serverManager = serverManager,
          metadata = metadata,
          paperJar = runningState!!.paperJar,
          hotReload = true,
      )
}

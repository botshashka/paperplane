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

internal open class RestartMode(
    private val session: DevSession,
    private val serverManager: PaperServerManager =
        PaperServerManager(
            File(session.ppDir, "server"),
            session.downloader,
            session.ui,
            protocolLog = session.config.dev.protocolLog,
        ),
) {
  // The instant fast lane and its confirmed-loaded baseline. Internal so tests can seed/inspect.
  internal val lane = InstantLane(session)
  internal val baseline = BaselineTracker()

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

    val state: RunningState =
        when (val outcome = runStartup()) {
          is StartupOutcome.Running -> outcome.state
          StartupOutcome.BuildFailed,
          StartupOutcome.LoadFailed -> enterFixRecovery() ?: return
          StartupOutcome.Aborted -> return
        }

    session.runMainWatchLoop(
        onChanged = { _ -> rebuild(state.metadata, state.paperJar) },
        onForceSwap = { rebuild(state.metadata, state.paperJar, forceFullSwap = true) },
        healthCheck = {
          // hasExitedUnexpectedly, not !isRunning(): rebuild() stops and restarts the server
          // inside the watcher callback, and this check polls concurrently from the main loop —
          // an intentional restart (or a build failure waiting for a fix with the server down)
          // must not read as a crash.
          if (serverManager.hasExitedUnexpectedly()) {
            session.ui.error("Server process exited unexpectedly")
            false
          } else true
        },
        cleanup = {
          serverManager.stop()
          session.syncOpsBackToConfig(serverManager)
          session.gradle.close()
        },
    )
  }

  /**
   * Runs the startup sequence (metadata → build → paper download → server start → info) inside a
   * single phase. Returns [StartupOutcome.Running] on success, [StartupOutcome.BuildFailed] if the
   * initial build failed or [StartupOutcome.LoadFailed] if the plugin was rejected at load (both
   * route to fix recovery), or [StartupOutcome.Aborted] for unrecoverable failures (metadata
   * missing, server failed to start).
   */
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
          when (val result = session.startServerAndReport(serverManager, metadata, paperJar)) {
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
          "restart",
          instantLabel = lane.capabilityLabel(serverManager, metadata),
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
          is DevSession.FixAttempt.Success ->
              // Null on a still-failing load keeps the fix-recovery loop waiting for the next edit.
              session
                  .startServerAndReport(serverManager, attempt.metadata, attempt.paperJar)
                  .stateOrNull
                  ?.also { lane.confirmFullSwap(baseline) }
        }
      }

  /**
   * The instant lane runs first, against the *live* server — the whole point is skipping the
   * stop/boot cycle, so the server must not be stopped until the lane has decided to fall through.
   * [forceFullSwap] is the manual escape hatch: skip the lane, restart on the current build.
   */
  internal fun rebuild(
      metadata: ProjectMetadata,
      paperJar: File,
      forceFullSwap: Boolean = false,
  ): PhaseEnd {
    val totalStart = System.currentTimeMillis()

    if (!forceFullSwap) {
      lane.runOrEscalate(serverManager, metadata, baseline, totalStart, "full restart")?.let {
        return it
      }
    }

    serverManager.stop()

    val buildStart = System.currentTimeMillis()
    val buildSuccess = session.gradle.build()
    val buildDuration = session.formatDuration(System.currentTimeMillis() - buildStart)

    if (!buildSuccess) {
      session.ui.error("Build failed", buildDuration)
      return PhaseEnd.Waiting
    }
    session.ui.success("Build succeeded", buildDuration)

    // Native deploy: the fresh jar goes straight into plugins/ so the restarted Paper loads it
    // itself. Staging (hot-reload's stagePlugin) would leave plugins/ holding the OLD jar and the
    // restarted server would boot without the user's new code.
    val builtJar = File(session.projectDir, metadata.jarPath)
    serverManager.copyPluginToPluginsDir(builtJar)
    serverManager.copyCompanion()

    val serverStart = System.currentTimeMillis()
    serverManager.start(paperJar, session.launchSpec)
    val ready = serverManager.waitForReady()
    val serverDuration = session.formatDuration(System.currentTimeMillis() - serverStart)

    return if (ready) {
      session.ui.success("Server ready", serverDuration)
      val totalDuration = session.formatDuration(System.currentTimeMillis() - totalStart)
      session.ui.totalTime(totalDuration)
      serverManager.sendCompanionStatus(CompanionWire.STATE_READY, duration = totalDuration)
      lane.confirmFullSwap(baseline)
      PhaseEnd.Watching
    } else {
      session.ui.error("Server failed to start", serverDuration)
      baseline.reset()
      PhaseEnd.Waiting
    }
  }
}

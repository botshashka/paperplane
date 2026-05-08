package dev.paperplane.cli.devserver

import dev.paperplane.cli.devserver.DevSession.RunningState
import dev.paperplane.cli.devserver.DevSession.StartupOutcome
import dev.paperplane.cli.gradle.MetadataResult
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.ui.TerminalUI.PhaseEnd
import java.io.File

internal open class RestartMode(
    private val session: DevSession,
    private val serverManager: PaperServerManager =
        PaperServerManager(File(session.ppDir, "server"), session.downloader, session.ui),
) {

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
          StartupOutcome.BuildFailed -> enterFixRecovery() ?: return
          StartupOutcome.Aborted -> return
        }

    session.runMainWatchLoop(
        onChanged = { _ -> rebuild(state.metadata, state.paperJar) },
        healthCheck = {
          if (!serverManager.isRunning()) {
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
   * initial build failed (caller should enter fix recovery), or [StartupOutcome.Aborted] for
   * unrecoverable failures (metadata missing, server failed to start).
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
          session.startServerAndReport(serverManager, metadata, paperJar)
              ?: return@phase PhaseEnd.None
      session.showServerInfo(
          metadata,
          "localhost:${PaperServerManager.DEFAULT_PORT}",
          "restart",
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
              session.startServerAndReport(serverManager, attempt.metadata, attempt.paperJar)
        }
      }

  internal fun rebuild(metadata: ProjectMetadata, paperJar: File): PhaseEnd {
    val totalStart = System.currentTimeMillis()
    serverManager.stop()

    val buildStart = System.currentTimeMillis()
    val buildSuccess = session.gradle.build()
    val buildDuration = session.formatDuration(System.currentTimeMillis() - buildStart)

    if (!buildSuccess) {
      session.ui.error("Build failed", buildDuration)
      return PhaseEnd.Waiting
    }
    session.ui.success("Build succeeded", buildDuration)

    val builtJar = File(session.projectDir, metadata.jarPath)
    serverManager.copyPlugin(builtJar)
    serverManager.copyCompanion()

    val serverStart = System.currentTimeMillis()
    serverManager.start(paperJar, session.config.server.jvmArgs)
    val ready = serverManager.waitForReady()
    val serverDuration = session.formatDuration(System.currentTimeMillis() - serverStart)

    return if (ready) {
      session.ui.success("Server ready", serverDuration)
      val totalDuration = session.formatDuration(System.currentTimeMillis() - totalStart)
      session.ui.totalTime(totalDuration)
      serverManager.writeCompanionStatus("ready", mapOf("duration" to totalDuration))
      PhaseEnd.Watching
    } else {
      session.ui.error("Server failed to start", serverDuration)
      PhaseEnd.Waiting
    }
  }
}

package dev.paperplane.cli.devserver

import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.ui.TerminalUI.PhaseEnd
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal open class RestartMode(
    private val session: DevSession,
    private val serverManager: PaperServerManager =
        PaperServerManager(File(session.ppDir, "server"), session.downloader, session.ui),
) {

  internal data class RunningState(val metadata: ProjectMetadata, val paperJar: File)

  fun run() {
    val shuttingDown = AtomicBoolean(false)
    Runtime.getRuntime()
        .addShutdownHook(
            Thread {
              if (!shuttingDown.get()) {
                session.ui.clearPinnedFooter()
              }
              serverManager.stop()
              session.gradle.close()
            }
        )

    val state = runStartup(shuttingDown) ?: return

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
          session.gradle.close()
        },
    )
  }

  /**
   * Runs the startup sequence (metadata → build → paper download → server start → info) inside a
   * single phase. Returns the running state on success, or null on failure (in which case the
   * phase's trailing footer is PhaseEnd.None and the mode exits).
   *
   * On a failed initial build, transfers to the fix-recovery loop which blocks forever.
   */
  internal fun runStartup(shuttingDown: AtomicBoolean): RunningState? {
    var state: RunningState? = null
    session.ui.phase {
      val metadata = session.resolveMetadataOrAbort(shuttingDown) ?: return@phase PhaseEnd.None
      val paperJar =
          when (val outcome = session.initialBuild(metadata, serverManager)) {
            is DevSession.BuildOutcome.Success -> outcome.paperJar
            is DevSession.BuildOutcome.BuildFailed -> {
              // Enter the fix-recovery loop. It blocks on the file watcher and never returns
              // normally; on a successful fix it transitions into the main loop via
              // startAndReport, which also never returns.
              enterFixRecovery()
              return@phase PhaseEnd.Waiting
            }
          }
      if (!startServer(metadata, paperJar)) return@phase PhaseEnd.None
      session.showServerInfo(
          metadata,
          "localhost:${PaperServerManager.DEFAULT_PORT}",
          "restart",
      )
      state = RunningState(metadata, paperJar)
      PhaseEnd.Watching
    }
    return state
  }

  /** Tests override this to a no-op so build-failure paths don't enter the infinite fix loop. */
  protected open fun enterFixRecovery(): Nothing {
    session.runFixWatcher(
        cleanup = {
          serverManager.stop()
          session.gradle.close()
        },
    ) {
      when (val attempt = session.handleFixAttempt(serverManager)) {
        is DevSession.FixAttempt.BuildFailed -> PhaseEnd.Waiting
        is DevSession.FixAttempt.Success -> {
          session.startServerAndReport(serverManager, attempt.metadata, attempt.paperJar)
          // We still need to hand off to the main watch loop. In practice the fix watcher
          // stays in control here — a fuller restructure would be to have the fix watcher
          // return to run() so the main loop could take over, but that's a bigger rework.
          PhaseEnd.Watching
        }
      }
    }
    error("fix recovery loop returned")
  }

  private fun startServer(metadata: ProjectMetadata, paperJar: File): Boolean {
    serverManager.cleanupStale()
    serverManager.configure()
    val builtJar = File(session.projectDir, metadata.jarPath)
    serverManager.copyPlugin(builtJar)
    serverManager.copyCompanion()

    val mcVersion = session.resolveMcVersion(metadata)
    val serverStart = System.currentTimeMillis()
    serverManager.start(paperJar, session.config.server.jvmArgs)
    val ready =
        session.ui.spin("Starting Paper $mcVersion server...") { serverManager.waitForReady() }
    val serverDuration = session.formatDuration(System.currentTimeMillis() - serverStart)

    return if (ready) {
      session.ui.success("Paper $mcVersion server ready", serverDuration)
      serverManager.writeCompanionStatus("ready", mapOf("duration" to serverDuration))
      true
    } else {
      session.ui.error("Server failed to start", serverDuration)
      serverManager.writeCompanionStatus("error", mapOf("message" to "Server failed to start"))
      false
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

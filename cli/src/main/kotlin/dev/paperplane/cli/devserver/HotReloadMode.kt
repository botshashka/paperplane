package dev.paperplane.cli.devserver

import dev.paperplane.cli.devserver.DevSession.RunningState
import dev.paperplane.cli.devserver.DevSession.StartupOutcome
import dev.paperplane.cli.gradle.BuildSnapshot
import dev.paperplane.cli.gradle.ClassChanges
import dev.paperplane.cli.gradle.MetadataResult
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.ui.TerminalUI.PhaseEnd
import java.io.File

internal open class HotReloadMode(
    private val session: DevSession,
    private val serverManager: PaperServerManager =
        PaperServerManager(File(session.ppDir, "server"), session.downloader, session.ui),
) {
  internal companion object {
    private const val RELOAD_TIMEOUT_MS = 10_000L

    /**
     * Pure helper: build a [LoadRequest] from a rebuild's inputs. Extracted so the strategy
     * selection (HOTSWAP vs DIRECTORY vs JAR) can be tested without standing up a full
     * HotReloadMode.
     *
     * Strategy selection rules:
     * - No fastMeta or no classesDir → JAR fallback (empty classesDirs, empty changedClasses).
     * - Have fastMeta + only modified classes (no add/remove) → HOTSWAP (changedClasses populated).
     * - Have fastMeta + structural change → DIRECTORY (classesDirs populated, changedClasses
     *   empty).
     */
    internal fun buildLoadRequest(
        metadata: ProjectMetadata,
        fastMeta: ProjectMetadata?,
        changes: ClassChanges,
        stagedJarPath: String,
    ): LoadRequest {
      val classesDirs: List<String>
      val resourcesDir: String
      val runtimeClasspath: List<String>
      val changedClasses: List<String>

      if (fastMeta != null && fastMeta.classesDir.isNotEmpty()) {
        classesDirs = fastMeta.effectiveClassesDirs
        resourcesDir = fastMeta.resourcesDir
        runtimeClasspath = fastMeta.runtimeClasspath
        changedClasses =
            if (changes.noNewOrRemovedClasses && changes.modified.isNotEmpty()) changes.modified
            else emptyList()
      } else {
        classesDirs = emptyList()
        resourcesDir = ""
        runtimeClasspath = emptyList()
        changedClasses = emptyList()
      }

      return LoadRequest(
          requestId = LoadRequest.newId(),
          jarPath = stagedJarPath,
          pluginName = metadata.pluginName,
          classesDirs = classesDirs,
          resourcesDir = resourcesDir,
          runtimeClasspath = runtimeClasspath,
          changedClasses = changedClasses,
      )
    }
  }

  private var buildSnapshot: BuildSnapshot? = null
  private var cachedFastMeta: ProjectMetadata? = null
  private var lastPostBuildSnapshot: Map<String, Long>? = null
  private val javaRuntime by lazy { session.resolveJava() }

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
        onChanged = { _ -> rebuild(state.metadata) },
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
                      jvmArgs = hotReloadJvmArgs(),
                      hotReload = true,
                      javaBin = javaRuntime.bin,
                  )
          ) {
            is DevSession.ServerStartResult.Running -> result.state
            DevSession.ServerStartResult.LoadFailed -> {
              outcome = StartupOutcome.LoadFailed
              return@phase PhaseEnd.Waiting
            }
            DevSession.ServerStartResult.Aborted -> return@phase PhaseEnd.None
          }
      session.showServerInfo(
          metadata,
          "localhost:${PaperServerManager.DEFAULT_PORT}",
          if (javaRuntime.isJbr) "hot-reload (enhanced — JBR)" else "hot-reload",
      )
      outcome = StartupOutcome.Running(state)
      PhaseEnd.Watching
    }
    return outcome
  }

  private fun hotReloadJvmArgs(): List<String> =
      if (javaRuntime.isJbr) session.config.server.jvmArgs + "-XX:+AllowEnhancedClassRedefinition"
      else session.config.server.jvmArgs

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
   * Starts the server after a successful fix-recovery rebuild. Must mirror [runStartup]'s startup
   * args — the agent/JBR wiring (`hotReload = true`, [hotReloadJvmArgs], the JBR [javaBin]) — so a
   * recovered server isn't silently downgraded to a plain JVM without the redefinition agent.
   * Returns null on a still-failing load so the fix-recovery loop keeps waiting for the next edit.
   */
  internal fun startAfterFix(attempt: DevSession.FixAttempt.Success): RunningState? =
      session
          .startServerAndReport(
              serverManager = serverManager,
              metadata = attempt.metadata,
              paperJar = attempt.paperJar,
              jvmArgs = hotReloadJvmArgs(),
              hotReload = true,
              javaBin = javaRuntime.bin,
          )
          .stateOrNull

  // ── Rebuild ──────────────────────────────────────────────────────────

  private fun rebuild(metadata: ProjectMetadata): PhaseEnd {
    val totalStart = System.currentTimeMillis()
    val preBuildSnapshot = snapshotBeforeBuild(metadata)

    if (cachedFastMeta == null) cachedFastMeta = session.gradle.metadataFast().metadataOrNull

    serverManager.writeCompanionStatus("building")
    val buildStart = System.currentTimeMillis()
    val buildSuccess = session.gradle.compileOnly()
    val buildDuration = session.formatDuration(System.currentTimeMillis() - buildStart)

    if (!buildSuccess) {
      session.ui.error("Build failed", buildDuration)
      serverManager.writeCompanionStatus("error", mapOf("message" to "Build failed"))
      return PhaseEnd.Waiting
    }
    session.ui.success("Build succeeded", buildDuration)

    val postBuildSnapshot = buildSnapshot!!.take()
    lastPostBuildSnapshot = postBuildSnapshot
    val changes = BuildSnapshot.diff(preBuildSnapshot, postBuildSnapshot)

    val requestId = triggerReload(metadata, changes)
    return waitAndReport(metadata, totalStart, requestId)
  }

  private fun snapshotBeforeBuild(metadata: ProjectMetadata): Map<String, Long> {
    val snapshotDir =
        if (cachedFastMeta != null && cachedFastMeta!!.effectiveClassesDirs.isNotEmpty()) {
          File(cachedFastMeta!!.effectiveClassesDirs.first())
        } else if (metadata.effectiveClassesDirs.isNotEmpty()) {
          metadata.effectiveClassesDirs.map { File(it) }.firstOrNull { it.exists() }
              ?: File(metadata.effectiveClassesDirs.first())
        } else {
          val javaDir = File(session.projectDir, "build/classes/java/main")
          val kotlinDir = File(session.projectDir, "build/classes/kotlin/main")
          if (javaDir.exists()) javaDir else kotlinDir
        }
    if (buildSnapshot == null) buildSnapshot = BuildSnapshot(snapshotDir)
    return lastPostBuildSnapshot ?: buildSnapshot!!.take()
  }

  /**
   * Writes the reload request and returns its requestId so [waitAndReport] can match the result.
   */
  private fun triggerReload(metadata: ProjectMetadata, changes: ClassChanges): String {
    val ppDir = File(serverManager.serverDir, ".paperplane")
    ppDir.mkdirs()
    LoadRequest.completeFlag(serverManager.serverDir).delete()
    LoadRequest.failedFlag(serverManager.serverDir).delete()

    // Always restage the user JAR so the host can fall back to a JAR load if directory load fails.
    val builtJar = File(session.projectDir, metadata.jarPath)
    if (!builtJar.exists()) session.gradle.build()
    val stagedJarPath = serverManager.copyPlugin(builtJar)

    val request = buildLoadRequest(metadata, cachedFastMeta, changes, stagedJarPath)
    when {
      request.classesDirs.isEmpty() -> session.ui.info("Strategy:", "jar (fallback)")
      request.changedClasses.isNotEmpty() ->
          session.ui.info("Strategy:", "hotswap (${changes.modified.size} modified)")
      else -> session.ui.info("Strategy:", "directory reload")
    }
    LoadRequest.write(serverManager.serverDir, request)
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
          session.loadResultWaiter.await(
              serverManager.serverDir,
              requestId,
              timeoutMs = RELOAD_TIMEOUT_MS,
              isAlive = serverManager::isRunning,
          )
        }
    val reloadDuration = session.formatDuration(System.currentTimeMillis() - reloadStart)

    return when (result) {
      is LoadWaitResult.Ok -> {
        session.ui.renderLeakWarnings(result.report)
        val totalDuration = session.formatDuration(System.currentTimeMillis() - totalStart)
        session.ui.success("Plugin reloaded", reloadDuration)
        session.ui.totalTime(totalDuration)
        serverManager.writeCompanionStatus("ready", mapOf("duration" to totalDuration))
        PhaseEnd.Watching
      }
      is LoadWaitResult.Failed -> {
        session.ui.error("Reload failed: ${result.message}", reloadDuration)
        serverManager.writeCompanionStatus("error", mapOf("message" to "Hot-reload failed"))
        PhaseEnd.Watching
      }
      LoadWaitResult.TimedOut -> {
        session.ui.error(
            "Hot-reload failed (server still running with old plugin)",
            reloadDuration,
        )
        serverManager.writeCompanionStatus("error", mapOf("message" to "Hot-reload failed"))
        PhaseEnd.Watching
      }
      LoadWaitResult.ServerExited -> {
        session.ui.error("Server process exited during reload", reloadDuration)
        PhaseEnd.Waiting
      }
    }
  }
}

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
  companion object {
    private const val RELOAD_TIMEOUT_MS = 10_000L
    private const val RELOAD_POLL_INTERVAL_MS = 100L
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
          StartupOutcome.BuildFailed -> enterFixRecovery() ?: return
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
          session.startServerAndReport(
              serverManager = serverManager,
              metadata = metadata,
              paperJar = paperJar,
              jvmArgs = hotReloadJvmArgs(),
              hotReload = true,
              javaBin = javaRuntime.bin,
          ) ?: return@phase PhaseEnd.None
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
          is DevSession.FixAttempt.Success ->
              session.startServerAndReport(serverManager, attempt.metadata, attempt.paperJar)
        }
      }

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

    triggerReload(metadata, changes)
    return waitAndReport(metadata, totalStart)
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

  private fun triggerReload(metadata: ProjectMetadata, changes: ClassChanges) {
    val ppDir = File(serverManager.serverDir, ".paperplane")
    ppDir.mkdirs()
    LoadRequest.completeFlag(serverManager.serverDir).delete()
    LoadRequest.failedFlag(serverManager.serverDir).delete()

    // Always restage the user JAR so the host can fall back to a JAR load if directory load fails.
    val builtJar = File(session.projectDir, metadata.jarPath)
    if (!builtJar.exists()) session.gradle.build()
    val stagedJarPath = serverManager.copyPlugin(builtJar)

    val fastMeta = cachedFastMeta
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
      session.ui.info(
          "Strategy:",
          if (changedClasses.isNotEmpty()) "hotswap (${changes.modified.size} modified)"
          else "directory reload",
      )
    } else {
      classesDirs = emptyList()
      resourcesDir = ""
      runtimeClasspath = emptyList()
      changedClasses = emptyList()
      session.ui.info("Strategy:", "jar (fallback)")
    }

    LoadRequest.write(
        serverManager.serverDir,
        LoadRequest(
            requestId = LoadRequest.newId(),
            jarPath = stagedJarPath,
            pluginName = metadata.pluginName,
            classesDirs = classesDirs,
            resourcesDir = resourcesDir,
            runtimeClasspath = runtimeClasspath,
            changedClasses = changedClasses,
        ),
    )
  }

  private fun waitAndReport(metadata: ProjectMetadata, totalStart: Long): PhaseEnd {
    val reloadStart = System.currentTimeMillis()
    val success =
        session.ui.spin("Reloading ${metadata.pluginName}...") {
          waitForReloadResult(serverManager.serverDir, timeoutMs = RELOAD_TIMEOUT_MS)
        }
    val reloadDuration = session.formatDuration(System.currentTimeMillis() - reloadStart)

    return if (success) {
      val totalDuration = session.formatDuration(System.currentTimeMillis() - totalStart)
      session.ui.success("Plugin reloaded", reloadDuration)
      session.ui.totalTime(totalDuration)
      serverManager.writeCompanionStatus("ready", mapOf("duration" to totalDuration))
      PhaseEnd.Watching
    } else {
      session.ui.error("Hot-reload failed (server still running with old plugin)", reloadDuration)
      serverManager.writeCompanionStatus("error", mapOf("message" to "Hot-reload failed"))
      PhaseEnd.Watching
    }
  }

  private fun waitForReloadResult(serverDir: File, timeoutMs: Long): Boolean {
    val completeFlag = LoadRequest.completeFlag(serverDir)
    val failedFlag = LoadRequest.failedFlag(serverDir)
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
      if (completeFlag.exists()) {
        completeFlag.delete()
        return true
      }
      if (failedFlag.exists()) {
        val reason = failedFlag.readText()
        failedFlag.delete()
        session.ui.error("Reload failed: $reason")
        return false
      }
      Thread.sleep(RELOAD_POLL_INTERVAL_MS)
    }
    return false
  }
}

package dev.paperplane.cli.devserver

import dev.paperplane.cli.gradle.BuildSnapshot
import dev.paperplane.cli.gradle.ClassChanges
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.ui.TerminalUI.PhaseEnd
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
        onChanged = { _ -> rebuild(state.metadata) },
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

  internal fun runStartup(shuttingDown: AtomicBoolean): RunningState? {
    var state: RunningState? = null
    session.ui.phase {
      val metadata = session.resolveMetadataOrAbort(shuttingDown) ?: return@phase PhaseEnd.None
      val paperJar =
          when (val outcome = session.initialBuild(metadata, serverManager)) {
            is DevSession.BuildOutcome.Success -> outcome.paperJar
            is DevSession.BuildOutcome.BuildFailed -> {
              enterFixRecovery()
              return@phase PhaseEnd.Waiting
            }
          }
      if (!startServer(metadata, paperJar)) return@phase PhaseEnd.None
      session.showServerInfo(
          metadata,
          "localhost:${PaperServerManager.DEFAULT_PORT}",
          if (javaRuntime.isJbr) "hot-reload (enhanced — JBR)" else "hot-reload",
      )
      state = RunningState(metadata, paperJar)
      PhaseEnd.Watching
    }
    return state
  }

  /** Tests override to a no-op so build-failure paths don't enter the infinite fix loop. */
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

    val jvmArgs =
        if (javaRuntime.isJbr) session.config.server.jvmArgs + "-XX:+AllowEnhancedClassRedefinition"
        else session.config.server.jvmArgs

    val mcVersion = session.resolveMcVersion(metadata)
    val serverStart = System.currentTimeMillis()
    serverManager.start(paperJar, jvmArgs, hotReload = true, javaBin = javaRuntime.bin)
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

  // ── Rebuild ──────────────────────────────────────────────────────────

  private fun rebuild(metadata: ProjectMetadata): PhaseEnd {
    val totalStart = System.currentTimeMillis()
    val preBuildSnapshot = snapshotBeforeBuild(metadata)

    if (cachedFastMeta == null) cachedFastMeta = session.gradle.metadataFast()

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
    File(ppDir, "reload-complete").delete()
    File(ppDir, "reload-failed").delete()

    val fastMeta = cachedFastMeta
    if (fastMeta != null && fastMeta.classesDir.isNotEmpty()) {
      triggerDirectoryReload(metadata, fastMeta, changes)
    } else {
      triggerJarReload(metadata)
    }
  }

  private fun triggerDirectoryReload(
      metadata: ProjectMetadata,
      fastMeta: ProjectMetadata,
      changes: ClassChanges,
  ) {
    val allDirs: List<String> =
        fastMeta.effectiveClassesDirs + listOf(fastMeta.resourcesDir) + fastMeta.runtimeClasspath

    val strategy =
        if (changes.noNewOrRemovedClasses && changes.modified.isNotEmpty()) ReloadStrategy.HOTSWAP
        else ReloadStrategy.DIRECTORY
    session.ui.info(
        "Strategy:",
        if (strategy == ReloadStrategy.HOTSWAP) "hotswap (${changes.modified.size} modified)"
        else "directory reload",
    )

    val statusExtra =
        mutableMapOf<String, Any>(
            "pluginName" to metadata.pluginName,
            "jarFileName" to File(metadata.jarPath).name,
            "reloadStrategy" to strategy.key,
            "buildOutputDirs" to allDirs,
        )
    if (strategy == ReloadStrategy.HOTSWAP) {
      statusExtra["changedClasses"] = changes.modified
    }
    serverManager.writeCompanionStatus("reloading", statusExtra)
  }

  private fun triggerJarReload(metadata: ProjectMetadata) {
    session.ui.info("Strategy:", "jar (fallback)")
    val builtJar = File(session.projectDir, metadata.jarPath)
    if (!builtJar.exists()) session.gradle.build()
    val stagedName = serverManager.stagePlugin(builtJar)
    serverManager.writeCompanionStatus(
        "reloading",
        mapOf(
            "pluginName" to metadata.pluginName,
            "jarFileName" to builtJar.name,
            "pendingJar" to stagedName,
        ),
    )
  }

  private fun waitAndReport(metadata: ProjectMetadata, totalStart: Long): PhaseEnd {
    val ppDir = File(serverManager.serverDir, ".paperplane")
    val reloadStart = System.currentTimeMillis()
    val success =
        session.ui.spin("Reloading ${metadata.pluginName}...") {
          waitForReloadResult(ppDir, timeoutMs = RELOAD_TIMEOUT_MS)
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

  private fun waitForReloadResult(ppDir: File, timeoutMs: Long): Boolean {
    val completeFlag = File(ppDir, "reload-complete")
    val failedFlag = File(ppDir, "reload-failed")
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

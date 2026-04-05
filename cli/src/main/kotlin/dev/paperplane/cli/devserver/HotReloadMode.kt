package dev.paperplane.cli.devserver

import dev.paperplane.cli.gradle.BuildSnapshot
import dev.paperplane.cli.gradle.ClassChanges
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class HotReloadMode(private val session: DevSession) {
  companion object {
    private const val RELOAD_TIMEOUT_MS = 10_000L
    private const val RELOAD_POLL_INTERVAL_MS = 100L
  }

  private val serverManager = PaperServerManager(File(session.ppDir, "server"), session.downloader)

  private var buildSnapshot: BuildSnapshot? = null
  private var cachedFastMeta: ProjectMetadata? = null
  private var lastPostBuildSnapshot: Map<String, Long>? = null
  private val javaRuntime by lazy { session.resolveJava() }

  fun run() {
    val shuttingDown = AtomicBoolean(false)
    Runtime.getRuntime()
        .addShutdownHook(
            Thread {
              if (!shuttingDown.get()) {
                TerminalUI.discardBlock()
                println()
              }
              serverManager.stop()
              session.gradle.close()
            }
        )

    val metadata = session.resolveMetadataOrAbort(shuttingDown) ?: return
    val paperJar =
        session.initialBuild(metadata, serverManager) {
          session.runFixWatcher(
              cleanup = {
                serverManager.stop()
                session.gradle.close()
              }
          ) {
            session.handleFixAttempt(serverManager) { meta, jar -> startAndReport(meta, jar) }
          }
        } ?: return
    if (!startServer(metadata, paperJar)) return

    session.showServerInfo(
        metadata,
        "localhost:${PaperServerManager.DEFAULT_PORT}",
        if (javaRuntime.isJbr) "hot-reload (enhanced — JBR)" else "hot-reload",
    )

    session.runMainWatchLoop(
        onChanged = { rebuild(metadata) },
        healthCheck = {
          if (!serverManager.isRunning()) {
            TerminalUI.error("Server process exited unexpectedly")
            false
          } else true
        },
        cleanup = {
          serverManager.stop()
          session.gradle.close()
        },
    )
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
        TerminalUI.spin("Starting Paper $mcVersion server...") { serverManager.waitForReady() }
    val serverDuration = session.formatDuration(System.currentTimeMillis() - serverStart)

    if (ready) {
      TerminalUI.success("Paper $mcVersion server ready", serverDuration)
      serverManager.writeCompanionStatus("ready", mapOf("duration" to serverDuration))
    } else {
      TerminalUI.error("Server failed to start", serverDuration)
      serverManager.writeCompanionStatus("error", mapOf("message" to "Server failed to start"))
      TerminalUI.endBlock()
    }
    return ready
  }

  // ── Rebuild ──────────────────────────────────────────────────────────

  private fun rebuild(metadata: ProjectMetadata) {
    val totalStart = System.currentTimeMillis()
    val preBuildSnapshot = snapshotBeforeBuild(metadata)

    if (cachedFastMeta == null) cachedFastMeta = session.gradle.metadataFast()

    serverManager.writeCompanionStatus("building")
    val buildStart = System.currentTimeMillis()
    val buildSuccess = session.gradle.compileOnly()
    val buildDuration = session.formatDuration(System.currentTimeMillis() - buildStart)

    if (!buildSuccess) {
      TerminalUI.error("Build failed", buildDuration)
      serverManager.writeCompanionStatus("error", mapOf("message" to "Build failed"))
      TerminalUI.awaitChanges(watching = false)
      return
    }
    TerminalUI.success("Build succeeded", buildDuration)

    val postBuildSnapshot = buildSnapshot!!.take()
    lastPostBuildSnapshot = postBuildSnapshot
    val changes = BuildSnapshot.diff(preBuildSnapshot, postBuildSnapshot)

    triggerReload(metadata, changes, totalStart)
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

  private fun triggerReload(metadata: ProjectMetadata, changes: ClassChanges, totalStart: Long) {
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

    waitAndReport(metadata, ppDir, totalStart)
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
    TerminalUI.info(
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
    TerminalUI.info("Strategy:", "jar (fallback)")
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

  private fun waitAndReport(metadata: ProjectMetadata, ppDir: File, totalStart: Long) {
    val reloadStart = System.currentTimeMillis()
    val success =
        TerminalUI.spin("Reloading ${metadata.pluginName}...") {
          waitForReloadResult(ppDir, timeoutMs = RELOAD_TIMEOUT_MS)
        }
    val reloadDuration = session.formatDuration(System.currentTimeMillis() - reloadStart)

    if (success) {
      val totalDuration = session.formatDuration(System.currentTimeMillis() - totalStart)
      TerminalUI.success("Plugin reloaded", reloadDuration)
      TerminalUI.totalTime(totalDuration)
      serverManager.writeCompanionStatus("ready", mapOf("duration" to totalDuration))
    } else {
      TerminalUI.error("Hot-reload failed (server still running with old plugin)", reloadDuration)
      serverManager.writeCompanionStatus("error", mapOf("message" to "Hot-reload failed"))
    }
    TerminalUI.awaitChanges()
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
        TerminalUI.error("Reload failed: $reason")
        return false
      }
      Thread.sleep(RELOAD_POLL_INTERVAL_MS)
    }
    return false
  }

  private fun startAndReport(metadata: ProjectMetadata, paperJar: File) {
    session.startServerAndReport(serverManager, metadata, paperJar)
  }
}

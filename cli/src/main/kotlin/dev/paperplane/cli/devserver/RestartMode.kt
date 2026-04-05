package dev.paperplane.cli.devserver

import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class RestartMode(private val session: DevSession) {
  private val serverManager = PaperServerManager(File(session.ppDir, "server"), session.downloader)

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
        "restart",
    )

    session.runMainWatchLoop(
        onChanged = { rebuild(metadata, paperJar) },
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

    val mcVersion = session.resolveMcVersion(metadata)
    val serverStart = System.currentTimeMillis()
    serverManager.start(paperJar, session.config.server.jvmArgs)
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

  private fun rebuild(metadata: ProjectMetadata, paperJar: File) {
    val totalStart = System.currentTimeMillis()
    serverManager.stop()

    val buildStart = System.currentTimeMillis()
    val buildSuccess = session.gradle.build()
    val buildDuration = session.formatDuration(System.currentTimeMillis() - buildStart)

    if (!buildSuccess) {
      TerminalUI.error("Build failed", buildDuration)
      TerminalUI.awaitChanges(watching = false)
      return
    }
    TerminalUI.success("Build succeeded", buildDuration)

    val builtJar = File(session.projectDir, metadata.jarPath)
    serverManager.copyPlugin(builtJar)
    serverManager.copyCompanion()

    val serverStart = System.currentTimeMillis()
    serverManager.start(paperJar, session.config.server.jvmArgs)
    val ready = serverManager.waitForReady()
    val serverDuration = session.formatDuration(System.currentTimeMillis() - serverStart)

    if (ready) {
      TerminalUI.success("Server ready", serverDuration)
      val totalDuration = session.formatDuration(System.currentTimeMillis() - totalStart)
      TerminalUI.totalTime(totalDuration)
      serverManager.writeCompanionStatus("ready", mapOf("duration" to totalDuration))
    } else {
      TerminalUI.error("Server failed to start", serverDuration)
    }
    TerminalUI.awaitChanges()
  }

  private fun startAndReport(metadata: ProjectMetadata, paperJar: File) {
    session.startServerAndReport(serverManager, metadata, paperJar)
  }
}

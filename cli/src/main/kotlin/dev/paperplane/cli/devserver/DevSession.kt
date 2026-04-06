package dev.paperplane.cli.devserver

import dev.paperplane.cli.Versions
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.server.JbrDownloader
import dev.paperplane.cli.server.PaperDownloader
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.util.JavaRuntimeUtil
import dev.paperplane.cli.watcher.FileWatcher
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class DevSession(
    val config: PaperPlaneConfig,
    val ppDir: File,
    val gradle: GradleBridge,
    val downloader: PaperDownloader,
    val projectDir: File,
) {
  companion object {
    private const val MAIN_LOOP_POLL_INTERVAL_MS = 1000L
  }

  fun resolveMetadataOrAbort(shuttingDown: AtomicBoolean): ProjectMetadata? {
    TerminalUI.beginBlock()
    val metadata = TerminalUI.spin("Reading project metadata...") { gradle.metadata() }
    if (metadata == null) {
      pluginNotFoundError()
      TerminalUI.endBlock()
      shuttingDown.set(true)
      gradle.close()
      return null
    }
    ppDir.mkdirs()
    return metadata
  }

  fun resolveMcVersion(metadata: ProjectMetadata): String {
    val mcVersion = config.server.version ?: metadata.paperApiVersion
    val api = Versions.apiVersion(mcVersion)
    if (api !in Versions.SUPPORTED_API_VERSIONS) {
      throw IllegalArgumentException(
          "Paper $mcVersion (api-version $api) is not supported by this version of PaperPlane. " +
              "Supported: ${Versions.SUPPORTED_API_VERSIONS.sorted().joinToString(", ")}. " +
              "Please update PaperPlane or change your Paper version."
      )
    }
    return mcVersion
  }

  fun initialBuild(
      metadata: ProjectMetadata,
      serverManager: PaperServerManager,
      onBuildFailure: () -> Unit,
  ): File? {
    val buildStart = System.currentTimeMillis()
    serverManager.writeCompanionStatus("building")
    val buildSuccess = TerminalUI.spin("Building...") { gradle.build() }
    val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

    if (!buildSuccess) {
      TerminalUI.error("Build failed", buildDuration)
      serverManager.writeCompanionStatus("error", mapOf("message" to "Build failed"))
      TerminalUI.awaitChanges(watching = false)
      onBuildFailure()
      return null
    }
    TerminalUI.success("Build succeeded", buildDuration)

    val mcVersion = resolveMcVersion(metadata)
    return downloadPaper(mcVersion)
  }

  fun downloadPaper(mcVersion: String): File =
      TerminalUI.spin("Downloading Paper $mcVersion...") { downloader.download(mcVersion) }

  fun showServerInfo(metadata: ProjectMetadata, serverAddress: String, modeLabel: String) {
    TerminalUI.endBlock()
    TerminalUI.beginBlock()
    TerminalUI.info("Server:", serverAddress)
    TerminalUI.info("Plugin:", "${metadata.pluginName} v${metadata.version}")
    TerminalUI.info("Mode:", modeLabel)
    TerminalUI.awaitChanges()
  }

  fun runFixWatcher(cleanup: () -> Unit, onFix: () -> Unit) {
    val srcDir = File(projectDir, "src")
    val watcher =
        FileWatcher(srcDir, config.dev.debounceMs) { changedFiles ->
          TerminalUI.discardBlock()
          TerminalUI.beginBlock()
          val shortName = changedFiles.firstOrNull()?.let { File(it).name } ?: "files"
          TerminalUI.change("Change detected: $shortName")
          onFix()
        }
    watcher.start()

    try {
      while (true) Thread.sleep(MAIN_LOOP_POLL_INTERVAL_MS)
    } catch (_: InterruptedException) {
      watcher.stop()
      cleanup()
    }
  }

  fun handleFixAttempt(
      serverManager: PaperServerManager?,
      onReady: (metadata: ProjectMetadata, paperJar: File) -> Unit,
  ) {
    val buildStart = System.currentTimeMillis()
    serverManager?.writeCompanionStatus("building")
    val buildSuccess = gradle.build()
    val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

    if (!buildSuccess) {
      TerminalUI.error("Build failed", buildDuration)
      TerminalUI.awaitChanges(watching = false)
      return
    }
    TerminalUI.success("Build succeeded", buildDuration)

    val metadata = gradle.metadata() ?: return
    val mcVersion = resolveMcVersion(metadata)
    val paperJar = downloader.download(mcVersion)
    onReady(metadata, paperJar)
  }

  fun runMainWatchLoop(
      onChanged: (changedFiles: List<String>) -> Unit,
      healthCheck: () -> Boolean,
      cleanup: () -> Unit,
  ) {
    val srcDir = File(projectDir, "src")
    val watcher =
        FileWatcher(srcDir, config.dev.debounceMs) { changedFiles ->
          TerminalUI.discardBlock()
          TerminalUI.beginBlock()
          val shortName = changedFiles.firstOrNull()?.let { File(it).name } ?: "files"
          val extra = if (changedFiles.size > 1) " (+${changedFiles.size - 1} more)" else ""
          TerminalUI.change("Change detected: $shortName$extra")
          onChanged(changedFiles)
        }
    watcher.start()

    try {
      while (true) {
        Thread.sleep(MAIN_LOOP_POLL_INTERVAL_MS)
        if (!healthCheck()) {
          break
        }
      }
    } catch (_: InterruptedException) {} finally {
      watcher.stop()
      cleanup()
      TerminalUI.discardBlock()
    }
  }

  fun startServerAndReport(
      serverManager: PaperServerManager,
      metadata: ProjectMetadata,
      paperJar: File,
  ) {
    serverManager.cleanupStale()
    serverManager.configure()
    val builtJar = File(projectDir, metadata.jarPath)
    serverManager.copyPlugin(builtJar)
    serverManager.copyCompanion()

    val serverStart = System.currentTimeMillis()
    serverManager.start(paperJar, config.server.jvmArgs)
    val ready = serverManager.waitForReady()
    val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)
    if (ready) {
      TerminalUI.success("Server ready", serverDuration)
      serverManager.writeCompanionStatus("ready", mapOf("duration" to serverDuration))
    }
    TerminalUI.awaitChanges()
  }

  fun formatDuration(ms: Long): String = formatDurationMs(ms)

  data class JavaRuntime(val bin: String, val isJbr: Boolean)

  fun resolveJava(): JavaRuntime {
    return when (config.dev.jbr) {
      "auto" -> {
        if (checkIsJbr("java")) JavaRuntime("java", true) else JavaRuntime("java", false)
      }
      "on" -> {
        val jbrDownloader = JbrDownloader()
        val javaBin = TerminalUI.spin("Downloading JBR...") { jbrDownloader.download() }
        JavaRuntime(javaBin.absolutePath, true)
      }
      "off" -> JavaRuntime("java", false)
      else -> JavaRuntime(config.dev.jbr, checkIsJbr(config.dev.jbr))
    }
  }

  private fun pluginNotFoundError() {
    TerminalUI.error("PaperPlane Gradle plugin not found.")
    TerminalUI.endBlock()
    TerminalUI.beginBlock()
    TerminalUI.info("ppl init", "add PaperPlane to this project")
    TerminalUI.info("ppl create", "scaffold a new plugin")
  }

  private fun checkIsJbr(javaBin: String): Boolean = JavaRuntimeUtil.checkIsJbr(javaBin)
}

private const val FORMAT_THRESHOLD_MS = 1000

internal fun formatDurationMs(ms: Long): String {
  return if (ms >= FORMAT_THRESHOLD_MS) "%.1fs".format(ms / FORMAT_THRESHOLD_MS.toDouble())
  else "${ms}ms"
}

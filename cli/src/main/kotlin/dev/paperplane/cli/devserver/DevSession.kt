package dev.paperplane.cli.devserver

import dev.paperplane.cli.Versions
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.server.JbrDownloader
import dev.paperplane.cli.server.PaperDownloader
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.ui.TerminalUI.PhaseEnd
import dev.paperplane.cli.util.JavaRuntimeUtil
import dev.paperplane.cli.util.formatDurationMs
import dev.paperplane.cli.watcher.FileWatcher
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class DevSession(
    val config: PaperPlaneConfig,
    val ppDir: File,
    val gradle: GradleBridge,
    val downloader: PaperDownloader,
    val projectDir: File,
    val ui: TerminalUI,
) {
  companion object {
    private const val MAIN_LOOP_POLL_INTERVAL_MS = 1000L
  }

  /** Result of an initial-build-and-resolve attempt used during startup or fix recovery. */
  sealed class BuildOutcome {
    data class Success(val paperJar: File) : BuildOutcome()

    object BuildFailed : BuildOutcome()
  }

  /** Result of a fix-watcher build attempt. */
  sealed class FixAttempt {
    data class Success(val metadata: ProjectMetadata, val paperJar: File) : FixAttempt()

    object BuildFailed : FixAttempt()
  }

  /**
   * Runs the metadata-resolution step. Emits directly into whatever block/phase the caller has
   * open; never touches block lifecycle. Returns null if the metadata can't be resolved (plugin not
   * applied) and signals shutdown via [shuttingDown].
   */
  fun resolveMetadataOrAbort(shuttingDown: AtomicBoolean): ProjectMetadata? {
    val metadata = ui.spin("Reading project metadata...") { gradle.metadata() }
    if (metadata == null) {
      pluginNotFoundError()
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

  /**
   * Runs the initial build + Paper download. Returns [BuildOutcome.Success] with the resolved Paper
   * jar on success, or [BuildOutcome.BuildFailed] if the build failed. Emits directly into the
   * caller's phase; never touches block lifecycle.
   */
  fun initialBuild(
      metadata: ProjectMetadata,
      serverManager: PaperServerManager,
  ): BuildOutcome {
    val buildStart = System.currentTimeMillis()
    serverManager.writeCompanionStatus("building")
    val buildSuccess = ui.spin("Building...") { gradle.build() }
    val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

    if (!buildSuccess) {
      ui.error("Build failed", buildDuration)
      serverManager.writeCompanionStatus("error", mapOf("message" to "Build failed"))
      return BuildOutcome.BuildFailed
    }
    ui.success("Build succeeded", buildDuration)

    val mcVersion = resolveMcVersion(metadata)
    val paperJar = downloadPaper(mcVersion)
    return BuildOutcome.Success(paperJar)
  }

  fun downloadPaper(mcVersion: String): File =
      ui.spin("Downloading Paper $mcVersion...") { downloader.download(mcVersion) }

  /**
   * Emits the three-line server summary (address / plugin / mode). Caller is inside a phase; this
   * function commits whatever the phase has emitted so far (build/server-ready lines) into a
   * separate visual sub-block above, then appends the info lines into a fresh sub-block.
   */
  fun showServerInfo(metadata: ProjectMetadata, serverAddress: String, modeLabel: String) {
    ui.nextSection()
    ui.info("Server:", serverAddress)
    ui.info("Plugin:", "${metadata.pluginName} v${metadata.version}")
    ui.info("Mode:", modeLabel)
  }

  /**
   * Blocks on the fix-recovery file watcher. On every change, wraps the [onFix] callback in a phase
   * whose trailing footer is determined by [onFix]'s returned [PhaseEnd]. A "Change detected"
   * change() line is prepended automatically inside the phase.
   */
  fun runFixWatcher(cleanup: () -> Unit, onFix: TerminalUI.() -> PhaseEnd) {
    val srcDir = File(projectDir, "src")
    val watcher =
        FileWatcher(srcDir, config.dev.debounceMs) { changedFiles ->
          ui.phase {
            val shortName = changedFiles.firstOrNull()?.let { File(it).name } ?: "files"
            change("Change detected: $shortName")
            onFix()
          }
        }
    watcher.start()

    try {
      while (true) Thread.sleep(MAIN_LOOP_POLL_INTERVAL_MS)
    } catch (_: InterruptedException) {
      watcher.stop()
      cleanup()
    }
  }

  /**
   * Runs a fix-watcher iteration body: rebuild, download the Paper jar if needed, return a
   * [FixAttempt] describing the outcome. Emits directly into the current phase.
   */
  fun handleFixAttempt(serverManager: PaperServerManager?): FixAttempt {
    val buildStart = System.currentTimeMillis()
    serverManager?.writeCompanionStatus("building")
    val buildSuccess = gradle.build()
    val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

    if (!buildSuccess) {
      ui.error("Build failed", buildDuration)
      return FixAttempt.BuildFailed
    }
    ui.success("Build succeeded", buildDuration)

    val metadata = gradle.metadata() ?: return FixAttempt.BuildFailed
    val mcVersion = resolveMcVersion(metadata)
    val paperJar = downloader.download(mcVersion)
    return FixAttempt.Success(metadata, paperJar)
  }

  /**
   * Blocks on the main file watcher. On every change, wraps [onChanged] in a phase with an
   * automatic "Change detected" prefix. [healthCheck] runs between phases and can emit an error
   * into the pinned watching footer if it decides to exit the loop.
   */
  fun runMainWatchLoop(
      onChanged: TerminalUI.(changedFiles: List<String>) -> PhaseEnd,
      healthCheck: () -> Boolean,
      cleanup: () -> Unit,
  ) {
    val srcDir = File(projectDir, "src")
    val watcher =
        FileWatcher(srcDir, config.dev.debounceMs) { changedFiles ->
          ui.phase {
            val shortName = changedFiles.firstOrNull()?.let { File(it).name } ?: "files"
            val extra = if (changedFiles.size > 1) " (+${changedFiles.size - 1} more)" else ""
            change("Change detected: $shortName$extra")
            onChanged(changedFiles)
          }
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
      ui.clearPinnedFooter()
    }
  }

  /**
   * Starts a server after a successful fix-recovery build and transitions to the main loop. Returns
   * the [PhaseEnd] that the caller should emit.
   */
  fun startServerAndReport(
      serverManager: PaperServerManager,
      metadata: ProjectMetadata,
      paperJar: File,
  ): PhaseEnd {
    serverManager.cleanupStale()
    serverManager.configure()
    val builtJar = File(projectDir, metadata.jarPath)
    serverManager.copyPlugin(builtJar)
    serverManager.copyCompanion()

    val serverStart = System.currentTimeMillis()
    serverManager.start(paperJar, config.server.jvmArgs)
    val ready = serverManager.waitForReady()
    val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)
    return if (ready) {
      ui.success("Server ready", serverDuration)
      serverManager.writeCompanionStatus("ready", mapOf("duration" to serverDuration))
      PhaseEnd.Watching
    } else {
      ui.error("Server failed to start", serverDuration)
      serverManager.writeCompanionStatus("error", mapOf("message" to "Server failed to start"))
      PhaseEnd.Waiting
    }
  }

  fun formatDuration(ms: Long): String = formatDurationMs(ms)

  data class JavaRuntime(val bin: String, val isJbr: Boolean)

  fun resolveJava(): JavaRuntime {
    return when (config.dev.jbr) {
      "auto" -> JavaRuntime("java", checkIsJbr("java"))
      "on" -> {
        val jbrDownloader = JbrDownloader(ui)
        val javaBin = ui.spin("Downloading JBR...") { jbrDownloader.download() }
        JavaRuntime(javaBin.absolutePath, true)
      }
      "off" -> JavaRuntime("java", false)
      else -> JavaRuntime(config.dev.jbr, checkIsJbr(config.dev.jbr))
    }
  }

  private fun pluginNotFoundError() {
    ui.error("PaperPlane Gradle plugin not found.")
    ui.info("ppl init", "add PaperPlane to this project")
    ui.info("ppl create", "scaffold a new plugin")
  }

  private fun checkIsJbr(javaBin: String): Boolean = JavaRuntimeUtil.checkIsJbr(javaBin)
}

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
import java.util.concurrent.LinkedBlockingQueue
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

  /**
   * The live server + metadata pair shared between startup, fix recovery, and the main watch loop.
   */
  data class RunningState(val metadata: ProjectMetadata, val paperJar: File)

  /**
   * What a mode's [runStartup] produced. Modeled as a sealed type so the caller in `run()` handles
   * every case explicitly — in particular, `BuildFailed` signals that fix-recovery should be
   * entered and, on recovery success, control should return to `run()` so `runMainWatchLoop` can
   * take over. Prior to this refactor, fix-recovery was entered from inside `runStartup` via a
   * never-returning helper, which left the main watch loop permanently unreachable after an
   * initial-build failure.
   */
  sealed class StartupOutcome {
    data class Running(val state: RunningState) : StartupOutcome()

    /** Initial build failed — caller should enter fix recovery. */
    object BuildFailed : StartupOutcome()

    /** Unrecoverable (metadata missing, server failed to start, proxy failed, etc.). */
    object Aborted : StartupOutcome()
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
   * applied, compile error, or any other gradle failure) and signals shutdown via [shuttingDown].
   * The underlying [GradleBridge] has already emitted the specific cause.
   */
  fun resolveMetadataOrAbort(shuttingDown: AtomicBoolean): ProjectMetadata? {
    val metadata = ui.spin("Reading project metadata...") { gradle.metadata() }
    if (metadata == null) {
      metadataResolutionError()
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
   * Blocks on the fix-recovery file watcher until either (a) [onFix] returns a non-null
   * [RunningState] (the fix landed and a live server is ready to hand off to the main watch loop),
   * or (b) the thread is interrupted (Ctrl+C shutdown). On interrupt, [onShutdown] runs and the
   * function returns null. On successful recovery, the returned [RunningState] should be passed to
   * [runMainWatchLoop] so normal rebuild iteration resumes.
   *
   * Prior to this refactor this function was named `runFixWatcher`, blocked on `while (true)
   * Thread.sleep(...)` forever, and had no way to return a recovered state. That left all three
   * dev-server modes permanently stuck under the fix-recovery callback after an initial- build
   * failure — defeating hot-reload, defeating blue-green, and hiding the main-loop health check.
   * The blocking queue below is the handoff channel that replaces the broken sleep loop.
   */
  fun runFixRecoveryAndWait(
      onShutdown: () -> Unit,
      onFix: TerminalUI.(changedFiles: List<String>) -> RunningState?,
  ): RunningState? {
    val srcDir = File(projectDir, "src")
    val recovered = LinkedBlockingQueue<RunningState>(1)
    val watcher =
        FileWatcher(srcDir, config.dev.debounceMs) { changedFiles ->
          ui.phase {
            val shortName = changedFiles.firstOrNull()?.let { File(it).name } ?: "files"
            change("Change detected: $shortName")
            val state = onFix(changedFiles)
            if (state != null) {
              recovered.offer(state)
              PhaseEnd.None // caller opens its own watching footer post-handoff
            } else {
              PhaseEnd.Waiting
            }
          }
        }
    watcher.start()

    return try {
      recovered.take()
    } catch (_: InterruptedException) {
      onShutdown()
      null
    } finally {
      watcher.stop()
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
   * Starts a server after a successful fix-recovery build. Returns a [RunningState] on success (the
   * caller hands it off to [runMainWatchLoop]) or null on failure (the caller stays in fix
   * recovery).
   */
  fun startServerAndReport(
      serverManager: PaperServerManager,
      metadata: ProjectMetadata,
      paperJar: File,
  ): RunningState? {
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
      RunningState(metadata, paperJar)
    } else {
      ui.error("Server failed to start", serverDuration)
      serverManager.writeCompanionStatus("error", mapOf("message" to "Server failed to start"))
      null
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

  private fun metadataResolutionError() {
    ui.error("Could not read project metadata.")
    ui.info("ppl init", "add PaperPlane to an existing project")
    ui.info("ppl create", "scaffold a new plugin")
  }

  private fun checkIsJbr(javaBin: String): Boolean = JavaRuntimeUtil.checkIsJbr(javaBin)
}

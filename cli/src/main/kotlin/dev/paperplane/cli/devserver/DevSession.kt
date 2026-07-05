package dev.paperplane.cli.devserver

import dev.paperplane.cli.Versions
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.gradle.MetadataResult
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.plugins.ManagedPlugins
import dev.paperplane.cli.plugins.ModrinthClient
import dev.paperplane.cli.plugins.PluginCache
import dev.paperplane.cli.plugins.PluginLockfile
import dev.paperplane.cli.plugins.PluginResolver
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

internal class DevSession(
    config: PaperPlaneConfig,
    val ppDir: File,
    val gradle: GradleBridge,
    val downloader: PaperDownloader,
    val projectDir: File,
    val ui: TerminalUI,
    /**
     * Factory for the plugin resolver used by [syncDependencyPlugins]. Defaults to a real resolver
     * that hits Modrinth and writes to `.paperplane/cache/plugins/`. Tests inject a fake
     * resolver/cache pair so they can verify the wiring without network or filesystem downloads.
     */
    private val pluginResolverFactory: () -> PluginResolver = {
      PluginResolver(ModrinthClient(), PluginCache(File(ppDir, "cache/plugins")))
    },
    /**
     * Waits for the host to answer a load request. Injected so tests can supply a scripted waiter
     * (the render fixtures don't run a real companion, so a real waiter would block until timeout).
     */
    internal val loadResultWaiter: LoadResultWaiter = PollingLoadResultWaiter(ui),
) {
  /**
   * Holds the current config. This is `var` because the reverse-sync of `server.ops` on shutdown
   * may update it (and persist it back to `paperplane.yml`) based on the companion plugin's runtime
   * auto-ops. All other config fields are treated as immutable for the lifetime of the session.
   */
  var config: PaperPlaneConfig = config
    private set

  companion object {
    private const val MAIN_LOOP_POLL_INTERVAL_MS = 1000L
    /**
     * How long to wait for the host to report the initial plugin load. Longer than a reload budget
     * because the first load races the tail of server startup (worlds, datapacks) on a cold JVM.
     */
    private const val INITIAL_LOAD_TIMEOUT_MS = 30_000L
  }

  /**
   * Build-config files watched alongside `src/`. A change in any of these regenerates `plugin.yml`
   * (commands/permissions block) or alters dependencies, so it must trigger a rebuild — the same
   * way a source edit does. Both Kotlin and Groovy DSL variants are listed; [FileWatcher] tolerates
   * the ones that don't exist on disk.
   */
  private val buildConfigFiles: List<File>
    get() =
        listOf(
            File(projectDir, "build.gradle.kts"),
            File(projectDir, "build.gradle"),
            File(projectDir, "settings.gradle.kts"),
            File(projectDir, "settings.gradle"),
            File(projectDir, "gradle.properties"),
        )

  /**
   * If any of [changedFiles] is one of our [buildConfigFiles], drop the cached Tooling API
   * connection so the next build/compile reconnects with a freshly-evaluated script.
   *
   * The Tooling API caches build-script evaluation on the
   * [ProjectConnection][org.gradle.tooling.ProjectConnection], so an in-session edit to
   * `build.gradle.kts` (e.g. removing a `commands { create("ping") }` block) doesn't propagate
   * until the connection is torn down — without this we'd keep regenerating stale `plugin.yml`s and
   * re-registering removed commands until the user restarts `ppl dev`.
   *
   * Source-only edits skip this entirely, so the fast path is unchanged.
   */
  internal fun maybeInvalidateGradleConnection(changedFiles: List<String>) {
    val buildConfigPaths =
        buildConfigFiles.map { FileWatcher.normalizePath(it.absolutePath) }.toSet()
    if (changedFiles.any { it in buildConfigPaths }) {
      gradle.close()
    }
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
   * Runs the metadata-resolution step inside a spinner and emits the appropriate framing for each
   * outcome:
   * - [MetadataResult.Success]: silently creates `.paperplane/`.
   * - [MetadataResult.PluginNotApplied]: prints the "ppl init / ppl create" hint and closes the
   *   gradle connection (caller will return without entering fix-recovery).
   * - [MetadataResult.TaskFailed]: prints "Build failed" with timing so the user sees the same UI
   *   they would for a build failure proper. Caller routes to fix-recovery.
   */
  fun resolveMetadata(): MetadataResult {
    val started = System.currentTimeMillis()
    val result = ui.spin("Reading project metadata...") { gradle.metadata() }
    when (result) {
      is MetadataResult.Success -> ppDir.mkdirs()
      MetadataResult.PluginNotApplied -> {
        metadataResolutionError()
        gradle.close()
      }
      MetadataResult.TaskFailed -> {
        val duration = formatDuration(System.currentTimeMillis() - started)
        ui.error("Build failed", duration)
      }
    }
    return result
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
   * Reconciles `server.plugins` from config with the on-disk lockfile, downloads any missing JARs,
   * copies them into the server plugins directory, and prunes removed entries. Writes the (possibly
   * updated) lockfile back to disk. Emits a "Plugins" summary line when plugins are present; stays
   * silent when the list is empty. Shows a spinner only when a network call is actually needed —
   * the "offline resilience" design goal.
   */
  internal fun syncDependencyPlugins(serverManager: PaperServerManager) {
    val deps = config.server.plugins
    if (deps.isEmpty()) {
      // Still prune — user may have removed the last plugin entry.
      ManagedPlugins.prune(serverManager.serverDir, emptySet())
      PluginLockfile.delete(projectDir)
      return
    }
    val mcVersion = config.server.version
    val resolver = pluginResolverFactory()
    val previous = PluginLockfile.load(projectDir)

    // Show a spinner only when the lockfile is incomplete or stale relative to config — that's
    // the path that may hit Modrinth. When everything is already locked, sync() reduces to a
    // file-existence + SHA verify per entry, which is microseconds and silent is better than
    // flash-of-spinner.
    val mayHitNetwork = deps.any { dep ->
      val existing = previous.find(dep.slug)
      existing == null ||
          (dep.version != null && dep.version != existing.version) ||
          dep.source.key != existing.source
    }

    val result =
        if (mayHitNetwork) {
          ui.spin("Resolving plugins...") { resolver.sync(deps, previous, mcVersion) }
        } else {
          resolver.sync(deps, previous, mcVersion)
        }

    if (result.lockfile != previous) {
      PluginLockfile.save(projectDir, result.lockfile)
    }
    val pluginsDir = File(serverManager.serverDir, "plugins")
    ManagedPlugins.copyJars(result.jars, pluginsDir)
    ManagedPlugins.prune(serverManager.serverDir, result.jars.map { it.name }.toSet())

    if (result.summary.isNotEmpty()) {
      ui.info("Plugins", result.summary.joinToString(", "))
    }
  }

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
        FileWatcher(srcDir, config.dev.debounceMs, extraFiles = buildConfigFiles) { changedFiles ->
          maybeInvalidateGradleConnection(changedFiles)
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

    val metadata = gradle.metadata().metadataOrNull ?: return FixAttempt.BuildFailed
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
        FileWatcher(srcDir, config.dev.debounceMs, extraFiles = buildConfigFiles) { changedFiles ->
          maybeInvalidateGradleConnection(changedFiles)
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
   * Starts a Paper server and emits the standard cleanupStale → configure → copy plugin/companion →
   * start → waitForReady → success/error ribbon. Used by initial startup in all three modes and by
   * the fix-recovery callback. Returns a [RunningState] on success or null on failure.
   *
   * Optional parameters let each mode tailor the flow:
   * - [jvmArgs] defaults to [config].server.jvmArgs; HotReloadMode appends
   *   `-XX:+AllowEnhancedClassRedefinition` when JBR is active.
   * - [hotReload] and [javaBin] are forwarded to [PaperServerManager.start] — HotReloadMode sets
   *   them so the agent gets wired up and the JBR java binary is used.
   * - [extraConfigure] runs immediately after [PaperServerManager.configure] and before
   *   [PaperServerManager.copyPlugin]. BlueGreenMode uses it to inject
   *   [PaperServerManager.configureVelocityForwarding], which must overwrite paper-global.yml that
   *   `configure()` just wrote.
   * - [spinLabel] is the spinner message while waiting for the server. Defaults to the standard
   *   "Starting Paper <mcVersion> server..." string.
   * - [readyMessage] is the success banner. Defaults to "Paper <mcVersion> server ready".
   */
  fun startServerAndReport(
      serverManager: PaperServerManager,
      metadata: ProjectMetadata,
      paperJar: File,
      jvmArgs: List<String> = config.server.jvmArgs,
      hotReload: Boolean = false,
      javaBin: String = "java",
      spinLabel: String = "Starting Paper ${resolveMcVersion(metadata)} server...",
      readyMessage: String = "Paper ${resolveMcVersion(metadata)} server ready",
      extraConfigure: (PaperServerManager) -> Unit = {},
  ): RunningState? {
    serverManager.cleanupStale()
    serverManager.configure(config.server)
    extraConfigure(serverManager)
    val builtJar = File(projectDir, metadata.jarPath)
    val stagedJarPath = serverManager.copyPlugin(builtJar)
    serverManager.copyCompanion(depend = metadata.depend, softdepend = metadata.softdepend)
    syncDependencyPlugins(serverManager)

    // Clear crashed-session leftovers so the fresh companion doesn't consume a stale request or the
    // waiter a stale result. Must happen before start() launches the companion's poll loop.
    clearProtocolFlags(serverManager.serverDir)

    val serverStart = System.currentTimeMillis()
    serverManager.start(paperJar, jvmArgs, hotReload = hotReload, javaBin = javaBin)
    val ready = ui.spin(spinLabel) { serverManager.waitForReady() }
    val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)
    if (!ready) {
      ui.error("Server failed to start", serverDuration)
      serverManager.writeCompanionStatus("error", mapOf("message" to "Server failed to start"))
      return null
    }

    // Companion is up and probed. Hand it the staged plugin to load, then wait for and consume the
    // result — this is what makes a rejected load (probe failure, unsupported plugin shape) visible
    // at startup instead of silently limping on.
    val requestId = writeInitialLoadRequest(serverManager, metadata, stagedJarPath)
    val loadResult =
        ui.spin("Loading ${metadata.pluginName}...") {
          loadResultWaiter.await(
              serverManager.serverDir,
              requestId,
              INITIAL_LOAD_TIMEOUT_MS,
              serverManager::isRunning,
          )
        }
    return when (loadResult) {
      is LoadWaitResult.Ok -> {
        ui.renderLeakWarnings(loadResult.report)
        ui.success("Plugin loaded")
        ui.success(readyMessage, serverDuration)
        serverManager.writeCompanionStatus("ready", mapOf("duration" to serverDuration))
        RunningState(metadata, paperJar)
      }
      is LoadWaitResult.Failed -> {
        ui.error("Plugin failed to load: ${loadResult.message}")
        serverManager.writeCompanionStatus("error", mapOf("message" to "Plugin load failed"))
        // Startup is declared failed — stop the server rather than leave it running unattended
        // until the next rebuild reaps it with a hard kill.
        serverManager.stop()
        null
      }
      LoadWaitResult.TimedOut -> {
        ui.error("Timed out waiting for the plugin to load")
        serverManager.writeCompanionStatus("error", mapOf("message" to "Plugin load timed out"))
        serverManager.stop()
        null
      }
      LoadWaitResult.ServerExited -> {
        ui.error("Server exited while loading the plugin")
        null
      }
    }
  }

  /** Writes the initial [LoadRequest] and returns its requestId for result matching. */
  private fun writeInitialLoadRequest(
      serverManager: PaperServerManager,
      metadata: ProjectMetadata,
      stagedJarPath: String,
  ): String {
    val requestId = LoadRequest.newId()
    LoadRequest.write(
        serverManager.serverDir,
        LoadRequest(
            requestId = requestId,
            jarPath = stagedJarPath,
            pluginName = metadata.pluginName,
            classesDirs = metadata.effectiveClassesDirs,
            resourcesDir = metadata.resourcesDir,
            runtimeClasspath = metadata.runtimeClasspath,
        ),
    )
    return requestId
  }

  /**
   * Deletes the file-protocol flags in `.paperplane/` so a crashed prior session's leftovers can't
   * be consumed by a fresh companion or the load-result waiter.
   */
  private fun clearProtocolFlags(serverDir: File) {
    LoadRequest.requestPath(serverDir).delete()
    LoadRequest.completeFlag(serverDir).delete()
    LoadRequest.failedFlag(serverDir).delete()
    PaperServerManager.companionErrorFlag(serverDir).delete()
  }

  /**
   * Reverse-sync of auto-opped players from the running server's `ops.json` back into
   * `paperplane.yml`. The companion plugin ops joining players via Bukkit API at runtime; Paper
   * persists that to `ops.json`. This walks the file, unions the names with `config.server.ops`
   * (preserving order; existing names keep their position, new names are appended), and if the set
   * changed, rewrites `paperplane.yml`. Best-effort — any failure is swallowed silently because
   * this runs during shutdown when there's no meaningful error path.
   *
   * Idempotent: safe to call multiple times from different shutdown paths.
   */
  fun syncOpsBackToConfig(serverManager: PaperServerManager) {
    try {
      val liveNames = serverManager.readOpNames()
      if (liveNames.isEmpty()) return
      val existing = config.server.ops
      val existingSet = existing.toSet()
      val banned = config.server.opBanlist.toSet()
      // Exclude existing names (already in list) and banned names (must never be auto-added).
      val newNames = liveNames.filter { it !in existingSet && it !in banned }
      if (newNames.isEmpty()) return
      val merged = existing + newNames
      val updated = config.copy(server = config.server.copy(ops = merged))
      PaperPlaneConfig.save(projectDir, updated)
      config = updated
    } catch (_: Exception) {
      // Best effort — shutdown path must not fail.
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

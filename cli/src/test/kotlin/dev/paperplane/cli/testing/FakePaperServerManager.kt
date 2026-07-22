package dev.paperplane.cli.testing

import dev.paperplane.cli.config.ServerConfig
import dev.paperplane.cli.devserver.LoadRequest
import dev.paperplane.cli.server.LaunchSpec
import dev.paperplane.cli.server.PaperDownloader
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.ui.TerminalUI
import java.io.File

/**
 * Test fake [PaperServerManager] that records every invocation and never spawns a real Paper
 * process (or dials a real companion socket). The fake's [start] synchronously emits the configured
 * [simulatedLogs] via [ui]`.serverLog(...)` so dev-server tests can verify how log lines interleave
 * above the pinned footer. [waitForReady] returns [readyResult]; [isRunning] returns
 * [runningResult]. Load requests are captured in [sentLoadRequests].
 *
 * Override the public state by reassigning the fields between phases of a multi-step test.
 */
class FakePaperServerManager(
    serverDir: File,
    downloader: PaperDownloader,
    private val ui: TerminalUI,
    var readyResult: Boolean = true,
    var runningResult: Boolean = true,
    var simulatedLogs: List<String> = emptyList(),
    var exitedUnexpectedly: Boolean = false,
) : PaperServerManager(serverDir, downloader, ui) {

  /** Ordered log of every method call for assertions. */
  val calls: MutableList<String> = mutableListOf()

  /** Every [LoadRequest] handed to [sendLoadRequest], in order. */
  val sentLoadRequests: MutableList<LoadRequest> = mutableListOf()

  /**
   * Every [LaunchSpec] handed to [start], in order. Tests assert that all entries are the same
   * session-wide spec — the structural "mirror the args" invariant.
   */
  val launchSpecs: MutableList<LaunchSpec> = mutableListOf()

  override fun cleanupStale() {
    calls += "cleanupStale"
  }

  override fun configure(serverConfig: ServerConfig) {
    calls += "configure"
  }

  override fun configureVelocityForwarding(secret: String) {
    calls += "configureVelocityForwarding"
  }

  override fun stagePlugin(jarPath: File): String {
    calls += "stagePlugin(${jarPath.name})"
    return "/fake/staged/${jarPath.name}"
  }

  override fun copyPluginToPluginsDir(jarPath: File) {
    calls += "copyPluginToPluginsDir(${jarPath.name})"
  }

  override fun removeDeployedPlugin(currentJarName: String) {
    calls += "removeDeployedPlugin($currentJarName)"
  }

  override fun copyCompanion(depend: List<String>, softdepend: List<String>) {
    calls += "copyCompanion(depend=${depend.size},softdepend=${softdepend.size})"
  }

  override fun start(paperJar: File, launch: LaunchSpec, attachAgent: Boolean) {
    calls += "start(${paperJar.name})"
    launchSpecs += launch
    // Stream the simulated server output through ui.serverLog so tests can verify the log lines
    // interleave correctly above the pinned footer (the most fragile rendering path).
    for (line in simulatedLogs) ui.serverLog(line)
  }

  override fun stop() {
    calls += "stop"
  }

  override fun isRunning(): Boolean {
    calls += "isRunning"
    return runningResult
  }

  override fun hasExitedUnexpectedly(): Boolean {
    calls += "hasExitedUnexpectedly"
    return exitedUnexpectedly
  }

  override fun waitForReady(): Boolean {
    calls += "waitForReady"
    return readyResult
  }

  override fun saveWorld(timeoutMs: Long): Boolean {
    calls += "saveWorld"
    return true
  }

  override fun sendCommand(command: String) {
    calls += "sendCommand($command)"
  }

  override fun sendCompanionStatus(state: String, duration: String?, message: String?) {
    calls += "sendCompanionStatus($state)"
  }

  override fun sendLoadRequest(request: LoadRequest): Boolean {
    calls += "sendLoadRequest(${request.pluginName})"
    sentLoadRequests += request
    return true
  }

  /** Scripted redefine capability the fake's "server" advertises. */
  var capability: dev.paperplane.cli.devserver.instant.RedefineCapability =
      dev.paperplane.cli.devserver.instant.RedefineCapability.BODY_ONLY

  /**
   * Scripted answer for [awaitInstantReport]; echoes the awaited requestId when OK, and reports
   * every requested class as applied — what a real companion does when nothing is skipped.
   */
  internal var instantWaitResult: (String) -> dev.paperplane.cli.devserver.InstantWaitResult =
      { id ->
        val requested = sentInstantSwaps.lastOrNull()
        dev.paperplane.cli.devserver.InstantWaitResult.Answered(
            dev.paperplane.cli.devserver.InstantSwapReport(
                requestId = id,
                status = dev.paperplane.cli.devserver.InstantSwapStatus.OK,
                patched = 1,
                appliedClasses =
                    (requested?.classes.orEmpty() + requested?.newClasses.orEmpty()).map {
                      it.fqcn
                    },
            )
        )
      }

  /** Whether [sendInstantSwap] reports the request as delivered. */
  internal var sendInstantSwapResult: Boolean = true

  /** Every [dev.paperplane.cli.devserver.InstantSwapRequest] handed to [sendInstantSwap]. */
  internal val sentInstantSwaps: MutableList<dev.paperplane.cli.devserver.InstantSwapRequest> =
      mutableListOf()

  override fun redefineCapability(): dev.paperplane.cli.devserver.instant.RedefineCapability {
    calls += "redefineCapability"
    return capability
  }

  override fun sendInstantSwap(request: dev.paperplane.cli.devserver.InstantSwapRequest): Boolean {
    calls += "sendInstantSwap(${request.pluginName})"
    sentInstantSwaps += request
    return sendInstantSwapResult
  }

  override fun awaitInstantReport(
      expectedRequestId: String,
      timeoutMs: Long,
  ): dev.paperplane.cli.devserver.InstantWaitResult {
    calls += "awaitInstantReport"
    return instantWaitResult(expectedRequestId)
  }
}

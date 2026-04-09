package dev.paperplane.cli.testing

import dev.paperplane.cli.config.ServerConfig
import dev.paperplane.cli.server.PaperDownloader
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.ui.TerminalUI
import java.io.File

/**
 * Test fake [PaperServerManager] that records every invocation and never spawns a real Paper
 * process. The fake's [start] synchronously emits the configured [simulatedLogs] via
 * [ui]`.serverLog(...)` so dev-server tests can verify how log lines interleave above the pinned
 * footer. [waitForReady] returns [readyResult]; [isRunning] returns [runningResult].
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
) : PaperServerManager(serverDir, downloader, ui) {

  /** Ordered log of every method call for assertions. */
  val calls: MutableList<String> = mutableListOf()

  override fun cleanupStale() {
    calls += "cleanupStale"
  }

  override fun configure(serverConfig: ServerConfig) {
    calls += "configure"
  }

  override fun configureVelocityForwarding(secret: String) {
    calls += "configureVelocityForwarding"
  }

  override fun copyPlugin(jarPath: File) {
    calls += "copyPlugin(${jarPath.name})"
  }

  override fun stagePlugin(jarPath: File): String {
    calls += "stagePlugin(${jarPath.name})"
    return "/fake/staged/${jarPath.name}.new"
  }

  override fun copyCompanion() {
    calls += "copyCompanion"
  }

  override fun start(paperJar: File, jvmArgs: List<String>, hotReload: Boolean, javaBin: String) {
    calls += "start(${paperJar.name}, hotReload=$hotReload)"
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

  override fun waitForReady(): Boolean {
    calls += "waitForReady"
    return readyResult
  }

  override fun waitForSave(timeoutMs: Long): Boolean {
    calls += "waitForSave"
    return true
  }

  override fun sendCommand(command: String) {
    calls += "sendCommand($command)"
  }

  override fun writeCompanionStatus(state: String, extra: Map<String, Any>) {
    calls += "writeCompanionStatus($state)"
  }
}

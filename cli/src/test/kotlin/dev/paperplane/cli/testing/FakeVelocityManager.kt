package dev.paperplane.cli.testing

import dev.paperplane.cli.server.VelocityManager
import dev.paperplane.cli.ui.TerminalUI
import java.io.File

/**
 * Test fake [VelocityManager] that records every invocation and never spawns a real Velocity
 * process. The fake's [start] synchronously emits the configured [simulatedLogs] via
 * [ui]`.serverLog(...)` so dev-server tests can verify how proxy log lines interleave above the
 * pinned footer.
 */
class FakeVelocityManager(
    proxyDir: File,
    private val ui: TerminalUI,
    var readyResult: Boolean = true,
    var runningResult: Boolean = true,
    var simulatedLogs: List<String> = emptyList(),
) : VelocityManager(proxyDir, ui) {

  /** Ordered log of every method call for assertions. */
  val calls: MutableList<String> = mutableListOf()

  override fun configure(serverPort: Int, swapPort: Int, proxyPort: Int) {
    calls += "configure(server=$serverPort, swap=$swapPort, proxy=$proxyPort)"
  }

  override fun start(velocityJar: File, javaBin: String) {
    calls += "start(${velocityJar.name})"
    for (line in simulatedLogs) ui.serverLog(line)
  }

  override fun stop() {
    calls += "stop"
  }

  override fun isRunning(): Boolean {
    calls += "isRunning"
    return runningResult
  }

  override fun waitForReady(port: Int): Boolean {
    calls += "waitForReady"
    return readyResult
  }

  override fun writeActiveServer(serverName: String, transfer: Boolean) {
    calls += "writeActiveServer($serverName, transfer=$transfer)"
  }

  override fun clearTransferComplete() {
    calls += "clearTransferComplete"
  }

  override fun waitForTransferComplete(timeoutMs: Long): Boolean {
    calls += "waitForTransferComplete"
    return true
  }
}

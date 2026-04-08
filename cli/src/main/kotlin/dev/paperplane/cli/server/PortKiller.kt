package dev.paperplane.cli.server

import dev.paperplane.cli.util.Platform
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val LSOF_TIMEOUT_SECONDS = 5L
private const val KILL_TIMEOUT_SECONDS = 2L
private const val PORT_RELEASE_DELAY_MS = 500L
private val WHITESPACE = Regex("\\s+")

/** Kills any process currently bound to [port]. Best-effort; swallows IOException. */
internal fun killProcessOnPort(port: Int) {
  try {
    if (Platform.isWindows) killPortProcessWindows(port) else killPortProcessUnix(port)
  } catch (_: IOException) {
    // Port detection may not be available on all systems
  }
}

private fun ProcessBuilder.captureOutput(timeoutSeconds: Long): String {
  val process = redirectErrorStream(true).start()
  val output = process.inputStream.bufferedReader().readText().trim()
  process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
  return output
}

private fun killPortProcessUnix(port: Int) {
  val pids = ProcessBuilder("lsof", "-ti", "tcp:$port").captureOutput(LSOF_TIMEOUT_SECONDS)
  if (pids.isEmpty()) return
  for (pid in pids.lines().filter { it.isNotBlank() }) {
    ProcessBuilder("kill", "-9", pid).start().waitFor(KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  }
  Thread.sleep(PORT_RELEASE_DELAY_MS)
}

private fun killPortProcessWindows(port: Int) {
  val output =
      ProcessBuilder("cmd", "/c", "netstat -ano | findstr :$port | findstr LISTENING")
          .captureOutput(LSOF_TIMEOUT_SECONDS)
  // Each line ends with a PID, e.g. "  TCP    0.0.0.0:25565    0.0.0.0:0    LISTENING    12345"
  val pids =
      output
          .lines()
          .filter { it.isNotBlank() }
          .mapNotNull { it.trim().split(WHITESPACE).lastOrNull() }
          .filter { it.all(Char::isDigit) }
          .toSet()
  if (pids.isEmpty()) return
  val cmd = mutableListOf("taskkill", "/F")
  for (pid in pids) {
    cmd.add("/PID")
    cmd.add(pid)
  }
  ProcessBuilder(cmd).start().waitFor(KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  Thread.sleep(PORT_RELEASE_DELAY_MS)
}

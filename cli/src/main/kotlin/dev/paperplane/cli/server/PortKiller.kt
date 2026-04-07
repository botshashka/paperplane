package dev.paperplane.cli.server

import dev.paperplane.cli.util.Platform
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val LSOF_TIMEOUT_SECONDS = 5L
private const val KILL_TIMEOUT_SECONDS = 2L
private const val PORT_RELEASE_DELAY_MS = 500L

/** Kills any process currently bound to [port]. Best-effort; swallows IOException. */
internal fun killProcessOnPort(port: Int) {
  try {
    if (Platform.isWindows) killPortProcessWindows(port) else killPortProcessUnix(port)
  } catch (_: IOException) {
    // Port detection may not be available on all systems
  }
}

private fun killPortProcessUnix(port: Int) {
  val lsof = ProcessBuilder("lsof", "-ti", "tcp:$port").redirectErrorStream(true).start()
  val pids = lsof.inputStream.bufferedReader().readText().trim()
  lsof.waitFor(LSOF_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  if (pids.isNotEmpty()) {
    for (pid in pids.lines().filter { it.isNotBlank() }) {
      ProcessBuilder("kill", "-9", pid).start().waitFor(KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }
    Thread.sleep(PORT_RELEASE_DELAY_MS)
  }
}

private fun killPortProcessWindows(port: Int) {
  val netstat =
      ProcessBuilder("cmd", "/c", "netstat -ano | findstr :$port | findstr LISTENING")
          .redirectErrorStream(true)
          .start()
  val output = netstat.inputStream.bufferedReader().readText().trim()
  netstat.waitFor(LSOF_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  // Each line ends with a PID, e.g. "  TCP    0.0.0.0:25565    0.0.0.0:0    LISTENING    12345"
  val pids =
      output
          .lines()
          .filter { it.isNotBlank() }
          .mapNotNull { it.trim().split("\\s+".toRegex()).lastOrNull() }
          .filter { it.all(Char::isDigit) }
          .toSet()
  if (pids.isNotEmpty()) {
    val cmd = mutableListOf("taskkill", "/F")
    for (pid in pids) {
      cmd.add("/PID")
      cmd.add(pid)
    }
    ProcessBuilder(cmd).start().waitFor(KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    Thread.sleep(PORT_RELEASE_DELAY_MS)
  }
}

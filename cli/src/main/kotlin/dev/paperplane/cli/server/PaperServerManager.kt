package dev.paperplane.cli.server

import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class PaperServerManager(
    val serverDir: File,
    private val downloader: PaperDownloader,
    private val port: Int = DEFAULT_PORT,
) {
  companion object {
    internal const val DEFAULT_PORT = 25565
    private const val GRACEFUL_STOP_TIMEOUT_SECONDS = 30L
    private const val SIGTERM_TIMEOUT_SECONDS = 5L
    private const val FORCE_STOP_TIMEOUT_SECONDS = 2L
    private const val SAVE_POLL_INTERVAL_MS = 200L
    private const val SERVER_READY_TIMEOUT_MS = 120_000L
    private const val READY_POLL_INTERVAL_MS = 100L
  }

  private var process: Process? = null
  private var processStdin: java.io.OutputStream? = null
  private val pluginsDir = File(serverDir, "plugins")
  private val gson = com.google.gson.Gson()

  /**
   * When true, log lines from this server's output thread are dropped instead of forwarded to the
   * TUI. Used by blue-green mode to silence the standby server while it's pre-warmed — otherwise
   * both backends' logs would interleave in the CLI output.
   */
  @Volatile var logSuppressed: Boolean = false

  /**
   * Cleans up stale state from a previous run that wasn't shut down cleanly. Kills any process
   * occupying the server port and removes world lock files.
   */
  fun cleanupStale() {
    killProcessOnPort(port)
    // Remove stale session.lock files from world directories
    if (serverDir.exists()) {
      serverDir
          .listFiles()
          ?.filter { it.isDirectory }
          ?.forEach { dir ->
            val lock = File(dir, "session.lock")
            if (lock.exists()) lock.delete()
          }
    }
  }

  fun configure() {
    serverDir.mkdirs()
    pluginsDir.mkdirs()
    // Always overwrite — PaperPlane manages these settings, and Paper rewrites
    // the file on first boot (making writeIfMissing a no-op for new properties)
    File(serverDir, "server.properties").writeText(ServerConfigs.serverProperties(port))
    writeIfMissing("bukkit.yml", ServerConfigs.bukkitYml)
    writeIfMissing("spigot.yml", ServerConfigs.spigotYml)
    val paperConfigDir = File(serverDir, "config").apply { mkdirs() }
    writeIfMissing(File(paperConfigDir, "paper-global.yml"), ServerConfigs.paperGlobalYml)
    writeIfMissing(
        File(paperConfigDir, "paper-world-defaults.yml"),
        ServerConfigs.paperWorldDefaultsYml,
    )
  }

  fun configureVelocityForwarding(secret: String) {
    val paperConfigDir = File(serverDir, "config")
    paperConfigDir.mkdirs()
    // Always overwrite paper-global.yml when proxy is enabled to ensure velocity settings are
    // correct
    File(paperConfigDir, "paper-global.yml")
        .writeText(
            """
            proxies:
              velocity:
                enabled: true
                online-mode: true
                secret: "$secret"
            timings:
              enabled: false
        """
                .trimIndent() + "\n"
        )
  }

  fun downloadServer(mcVersion: String): File {
    return downloader.download(mcVersion)
  }

  fun copyPlugin(jarPath: File) {
    val target = File(pluginsDir, jarPath.name)
    val temp = File(pluginsDir, ".${jarPath.name}.tmp")
    jarPath.copyTo(temp, overwrite = true)
    try {
      Files.move(
          temp.toPath(),
          target.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
      )
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  /**
   * Stages a plugin jar in .paperplane/ (not plugins/) so Paper doesn't delete it. Used in
   * hot-reload mode so the companion can roll back to the original on failure. Returns the absolute
   * path to the staged file.
   */
  fun stagePlugin(jarPath: File): String {
    val stageDir = File(serverDir, ".paperplane")
    stageDir.mkdirs()
    val staged = File(stageDir, "${jarPath.name}.new")
    jarPath.copyTo(staged, overwrite = true)
    return staged.absolutePath
  }

  fun copyCompanion() {
    extractResource("paperplane-companion.bin", File(pluginsDir, "paperplane-companion.jar"))
  }

  /**
   * Extracts the PaperPlane Java agent JAR from CLI resources. Used for HMR Level 2
   * (instrumentation-based hot-swap).
   */
  fun extractAgent(): File {
    val agentJar = File(serverDir, ".paperplane/paperplane-agent.jar")
    if (agentJar.exists()) return agentJar
    agentJar.parentFile.mkdirs()
    extractResource("paperplane-agent.bin", agentJar)
    return agentJar
  }

  private fun extractResource(resourceName: String, target: File) {
    val stream =
        javaClass.classLoader.getResourceAsStream(resourceName)
            ?: throw IOException("Resource '$resourceName' not found in CLI jar — corrupted build?")
    stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
  }

  fun start(
      paperJar: File,
      jvmArgs: List<String>,
      hotReload: Boolean = false,
      javaBin: String = "java",
  ): Process {
    val cmd = mutableListOf(javaBin)
    // Fast startup flags
    cmd.addAll(
        listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-XX:+UseG1GC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+DisableExplicitGC",
            "-XX:InitiatingHeapOccupancyPercent=75",
        )
    )

    if (hotReload) {
      val agentJar = extractAgent()
      cmd.add("-javaagent:${agentJar.absolutePath}")
    }

    cmd.addAll(jvmArgs)
    cmd.addAll(listOf("-jar", paperJar.absolutePath, "--nogui"))

    val pb = ProcessBuilder(cmd).directory(serverDir).redirectErrorStream(true)

    val proc = pb.start()
    process = proc
    processStdin = proc.outputStream

    Thread(
            {
              proc.inputStream.bufferedReader().forEachLine { line ->
                if (!logSuppressed) TerminalUI.serverLog("  ${formatServerLine(line)}")
              }
            },
            "server-$port-output",
        )
        .apply { isDaemon = true }
        .start()

    return proc
  }

  fun stop() {
    val proc = process ?: return
    if (!proc.isAlive) return
    val unit = java.util.concurrent.TimeUnit.SECONDS

    // Prefer Paper's own "stop" command — it runs the full shutdown sequence on the main thread
    // (disable plugins, save worlds, halt) which is more reliable and quieter than SIGTERM.
    val stdinSent =
        try {
          processStdin?.let {
            it.write("stop\n".toByteArray())
            it.flush()
            true
          } ?: false
        } catch (_: IOException) {
          false
        }

    if (stdinSent && proc.waitFor(GRACEFUL_STOP_TIMEOUT_SECONDS, unit)) {
      process = null
      processStdin = null
      return
    }

    // Graceful path didn't work — fall back to SIGTERM, then SIGKILL.
    proc.destroy()
    if (!proc.waitFor(SIGTERM_TIMEOUT_SECONDS, unit)) {
      proc.destroyForcibly()
      proc.waitFor(FORCE_STOP_TIMEOUT_SECONDS, unit)
    }
    process = null
    processStdin = null
  }

  fun sendCommand(command: String) {
    processStdin?.let {
      it.write("$command\n".toByteArray())
      it.flush()
    }
  }

  /**
   * Waits for the companion plugin to complete a save. The CLI writes "saving" to
   * companion-status.json, the companion does the save via Bukkit API (no broadcast), then writes a
   * flag file.
   */
  fun waitForSave(timeoutMs: Long = 10_000): Boolean {
    val flagFile = File(serverDir, ".paperplane/save-complete")
    flagFile.delete() // Clear any stale flag

    // The companion plugin polls every 1s and saves when it sees "saving" state.
    // We poll for the flag file it writes on completion.
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < timeoutMs) {
      if (flagFile.exists()) {
        flagFile.delete()
        return true
      }
      Thread.sleep(SAVE_POLL_INTERVAL_MS)
    }
    return false
  }

  fun isRunning(): Boolean = process?.isAlive == true

  fun waitForReady(): Boolean {
    val proc = process ?: return false
    val flagFile = File(serverDir, ".paperplane/server-ready")
    flagFile.delete() // Clear stale flag
    val startTime = System.currentTimeMillis()
    val timeout = SERVER_READY_TIMEOUT_MS
    while (proc.isAlive && System.currentTimeMillis() - startTime < timeout) {
      if (flagFile.exists()) {
        flagFile.delete()
        return true
      }
      Thread.sleep(READY_POLL_INTERVAL_MS)
    }
    return false
  }

  fun writeCompanionStatus(state: String, extra: Map<String, Any> = emptyMap()) {
    val statusDir = File(serverDir, ".paperplane")
    statusDir.mkdirs()
    val statusFile = File(statusDir, "companion-status.json")
    val map = mutableMapOf<String, Any>("state" to state, "protocolVersion" to 2)
    map.putAll(extra)
    val json = gson.toJson(map)
    val tmpFile = File(statusDir, ".companion-status.tmp")
    tmpFile.writeText(json)
    Files.move(tmpFile.toPath(), statusFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }

  private fun writeIfMissing(name: String, content: String) {
    writeIfMissing(File(serverDir, name), content)
  }

  private fun writeIfMissing(file: File, content: String) {
    if (!file.exists()) {
      file.parentFile.mkdirs()
      file.writeText(content)
    }
  }
}

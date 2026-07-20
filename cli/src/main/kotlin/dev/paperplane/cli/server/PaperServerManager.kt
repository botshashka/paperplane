package dev.paperplane.cli.server

import com.charleskorn.kaml.YamlMap
import dev.paperplane.cli.config.ServerConfig
import dev.paperplane.cli.devserver.LoadRequest
import dev.paperplane.cli.devserver.LoadWaitResult
import dev.paperplane.cli.ipc.CompanionClient
import dev.paperplane.cli.ipc.CompanionSocketFile
import dev.paperplane.cli.ipc.CompanionWire
import dev.paperplane.cli.ipc.ProtocolTee
import dev.paperplane.cli.plugins.atomicMoveOrFallback
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import java.io.IOException
import java.util.UUID

open class PaperServerManager(
    val serverDir: File,
    private val downloader: PaperDownloader,
    private val ui: TerminalUI,
    private val port: Int = DEFAULT_PORT,
    /** When true, every companion socket message is teed to `.paperplane/protocol-log.ndjson`. */
    private val protocolLog: Boolean = false,
) {
  companion object {
    internal const val DEFAULT_PORT = 25565
    private const val GRACEFUL_STOP_TIMEOUT_SECONDS = 30L
    private const val SIGTERM_TIMEOUT_SECONDS = 5L
    private const val FORCE_STOP_TIMEOUT_SECONDS = 2L
    private const val SAVE_TIMEOUT_MS = 10_000L
    /** Highest vanilla op permission level — full command access on the dev server. */
    private const val OP_PERMISSION_LEVEL = 4
    private const val SERVER_READY_TIMEOUT_MS = 120_000L

    /**
     * The flag the companion writes when it fails to enable — the one companion→CLI signal that
     * stays on disk, because a companion that failed to construct its socket endpoint has no
     * connection to report over. Polled by [waitForReady]'s dial loop; cleared before each start.
     */
    internal fun companionErrorFlag(serverDir: File): File =
        File(serverDir, ".paperplane/companion-error")
  }

  private var process: Process? = null
  private var processStdin: java.io.OutputStream? = null

  // The socket connection to this server's companion. Created fresh by each start() (the CLI owns
  // the connection and re-dials per server run); closed by stop(). Null before the first start and
  // in tests that fake this manager.
  private var companion: CompanionClient? = null

  // True from the moment a stop is requested until the next start. Distinguishes an intentional
  // shutdown (mode-driven restarts, cleanup) from the process dying on its own — see
  // [hasExitedUnexpectedly].
  @Volatile private var stopRequested = false
  private val pluginsDir = File(serverDir, "plugins")
  private val gson = com.google.gson.Gson()

  /**
   * The most recently merged `paper-global.yml` contents, captured by [configure]. Used by
   * [configureVelocityForwarding] so it can layer velocity settings on top of the user's
   * paperGlobal overrides without round-tripping through the filesystem.
   */
  private var lastPaperGlobalYml: String = ServerConfigs.paperGlobalYml

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
  open fun cleanupStale() {
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

  open fun configure(serverConfig: ServerConfig = ServerConfig()) {
    serverDir.mkdirs()
    pluginsDir.mkdirs()
    // paperplane.yml is the source of truth for all server configuration. Every configure() call
    // rewrites these files from the in-memory config, so direct edits in .paperplane/server/ are
    // not supported — change paperplane.yml instead.
    File(serverDir, ServerConfigs.SERVER_PROPERTIES_FILE)
        .writeText(ServerConfigs.serverProperties(port, serverConfig.properties))
    File(serverDir, ServerConfigs.BUKKIT_YML_FILE).writeText(ServerConfigs.bukkitYml)
    File(serverDir, ServerConfigs.SPIGOT_YML_FILE).writeText(ServerConfigs.spigotYml)
    val paperConfigDir = File(serverDir, ServerConfigs.PAPER_CONFIG_DIR).apply { mkdirs() }
    // Only paper-global's merge result is captured — configureVelocityForwarding() needs it to
    // layer velocity settings on top without re-reading the file. paper-world-defaults has no
    // such follow-up, so its return value is discarded.
    lastPaperGlobalYml =
        writeMerged(
            paperConfigDir,
            ServerConfigs.PAPER_GLOBAL_YML_FILE,
            ServerConfigs.paperGlobalYml,
            serverConfig.paperGlobal,
        )
    writeMerged(
        paperConfigDir,
        ServerConfigs.PAPER_WORLD_DEFAULTS_YML_FILE,
        ServerConfigs.paperWorldDefaultsYml,
        serverConfig.paperWorldDefaults,
    )
    val banned = serverConfig.opBanlist.toSet()
    writeOpsJson(serverConfig.ops.filter { it !in banned })
    writeOpBanlist(serverConfig.opBanlist)
  }

  /**
   * Merges [override] on top of [base] and writes the result to `[dir]/[name]`. Returns the merged
   * YAML so callers can cache it in memory instead of re-reading the file.
   */
  private fun writeMerged(dir: File, name: String, base: String, override: YamlMap?): String {
    val merged = YamlDeepMerge.merge(base, override)
    File(dir, name).writeText(merged)
    return merged
  }

  /**
   * Writes `ops.json` if [names] is non-empty. Uses offline-mode UUIDs (deterministic from name)
   * since the dev server runs with `online-mode=false`. PaperPlane's companion plugin also auto-ops
   * joining players at runtime — this list seeds known ops across fresh server directories.
   */
  private fun writeOpsJson(names: List<String>) {
    val opsFile = File(serverDir, "ops.json")
    if (names.isEmpty()) {
      opsFile.delete() // idempotent — no exists() pre-check
      return
    }
    val entries = names.map { name ->
      mapOf(
          "uuid" to offlineUuid(name).toString(),
          "name" to name,
          "level" to OP_PERMISSION_LEVEL,
          "bypassesPlayerLimit" to false,
      )
    }
    opsFile.writeText(gson.toJson(entries))
  }

  /**
   * Writes the op banlist to `.paperplane/op-banlist.json` as a JSON array of names. The companion
   * plugin reads this file on join events and skips auto-opping any listed name. Also consulted by
   * the CLI's reverse-sync to keep banned names out of `paperplane.yml`.
   */
  private fun writeOpBanlist(names: List<String>) {
    val statusDir = File(serverDir, ".paperplane").apply { mkdirs() }
    val file = File(statusDir, "op-banlist.json")
    if (names.isEmpty()) {
      file.delete()
      return
    }
    file.writeText(gson.toJson(names))
  }

  /** Deterministic UUID that Minecraft uses for offline-mode players. */
  private fun offlineUuid(name: String): UUID =
      UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))

  /**
   * Reads current op names from `ops.json` in this server's directory. Returns an empty list if the
   * file is missing or malformed. Used for reverse-sync of auto-opped players back into
   * `paperplane.yml`.
   */
  fun readOpNames(): List<String> {
    return try {
      @Suppress("UNCHECKED_CAST")
      val arr =
          gson.fromJson(File(serverDir, "ops.json").readText(), List::class.java)
              as? List<Map<String, Any>>
      arr?.mapNotNull { it["name"] as? String } ?: emptyList()
    } catch (_: Exception) {
      // ops.json missing, unreadable, or malformed — treat as no ops.
      emptyList()
    }
  }

  open fun configureVelocityForwarding(secret: String) {
    val paperConfigDir = File(serverDir, ServerConfigs.PAPER_CONFIG_DIR).apply { mkdirs() }
    // Layer velocity forwarding on top of the in-memory paper-global.yml that configure() just
    // built. Using [lastPaperGlobalYml] (not re-reading from disk) keeps user's paperGlobal
    // overrides without an unnecessary file round-trip. proxies.velocity.* is PaperPlane-managed
    // and must always win.
    val merged = YamlDeepMerge.merge(lastPaperGlobalYml, velocityOverlay(secret))
    File(paperConfigDir, ServerConfigs.PAPER_GLOBAL_YML_FILE).writeText(merged)
    lastPaperGlobalYml = merged
  }

  private fun velocityOverlay(secret: String): YamlMap {
    // Escape backslashes and double quotes so a pathological secret can't produce malformed YAML
    // or inject extra keys. The secret is internally generated today, but belt-and-braces keeps
    // this safe if the source ever changes.
    val escaped = secret.replace("\\", "\\\\").replace("\"", "\\\"")
    return yamlMap(
        """
        proxies:
          velocity:
            enabled: true
            online-mode: true
            secret: "$escaped"
        """
            .trimIndent() + "\n"
    )
  }

  fun downloadServer(mcVersion: String): File {
    return downloader.download(mcVersion)
  }

  /**
   * Stages the user's plugin JAR in `.paperplane/staged/`. Paper never sees this directory — the
   * companion (acting as host) loads the JAR via `InnerPluginHost`. Returns the absolute path. Used
   * by hot-reload mode.
   */
  open fun stagePlugin(jarPath: File): String =
      atomicCopyInto(File(serverDir, ".paperplane/staged"), jarPath).absolutePath

  /**
   * Copies the user's plugin JAR straight into `plugins/` so Paper loads it natively via its own
   * `PluginClassLoader`. Used by restart and blue-green mode, which are "compatible with
   * everything" (any Paper version, STARTUP plugins, `paper-plugin.yml`, NMS) precisely because
   * they don't route the plugin through the companion host. Atomic tmp + move so Paper never scans
   * a half-written jar.
   *
   * Records the deployed jar name so a later deploy under a different name (a version bump changes
   * the artifact filename) or a mode switch to hot-reload can remove it — a leftover copy in
   * `plugins/` would be loaded by Paper alongside the new one and rejected as a duplicate plugin,
   * or silently run stale code.
   */
  open fun copyPluginToPluginsDir(jarPath: File) {
    val record = deployedPluginRecord()
    val previous = if (record.isFile) record.readText().trim().takeIf { it.isNotEmpty() } else null
    if (previous != null && previous != jarPath.name) {
      File(pluginsDir, previous).delete()
    }
    atomicCopyInto(pluginsDir, jarPath)
    record.parentFile.mkdirs()
    record.writeText(jarPath.name)
  }

  /**
   * Removes a natively-deployed user jar from `plugins/`. Hot-reload calls this before starting: it
   * stages the jar for the companion host instead, and a jar left in `plugins/` by a previous
   * restart/blue-green session would be natively loaded alongside the host's copy — two live
   * instances, the native one running stale code. [currentJarName] is deleted as well to cover jars
   * deployed before the record existed.
   */
  open fun removeDeployedPlugin(currentJarName: String) {
    val record = deployedPluginRecord()
    if (record.isFile) {
      record.readText().trim().takeIf { it.isNotEmpty() }?.let { File(pluginsDir, it).delete() }
      record.delete()
    }
    File(pluginsDir, currentJarName).delete()
  }

  private fun deployedPluginRecord(): File = File(serverDir, ".paperplane/deployed-plugin")

  private fun atomicCopyInto(destDir: File, jar: File): File {
    destDir.mkdirs()
    val target = File(destDir, jar.name)
    val temp = File(destDir, ".${jar.name}.tmp")
    jar.copyTo(temp, overwrite = true)
    atomicMoveOrFallback(temp.toPath(), target.toPath())
    return target
  }

  /**
   * Extracts the embedded companion JAR, rewriting its `plugin.yml` to inherit the user's [depend]
   * / [softdepend] declarations. The rewrite matters only for hot-reload, where the user's plugin
   * is staged out of `plugins/` and the companion must claim its depends so Paper orders them
   * before the host loads the plugin. Native modes call this with no arguments: the user's plugin
   * sits in `plugins/` and Paper resolves its depends directly.
   */
  open fun copyCompanion(
      depend: List<String> = emptyList(),
      softdepend: List<String> = emptyList(),
  ) {
    val target = File(pluginsDir, "paperplane-companion.jar")
    val source =
        javaClass.classLoader.getResourceAsStream("paperplane-companion.bin")
            ?: throw IOException(
                "Resource 'paperplane-companion.bin' not found in CLI jar — corrupted build?"
            )
    val sourceBytes = source.use { it.readAllBytes() }
    CompanionJarRewriter.rewriteFromBytes(sourceBytes, target, depend, softdepend)
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

  open fun start(
      paperJar: File,
      jvmArgs: List<String>,
      hotReload: Boolean = false,
      javaBin: String = "java",
  ) {
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

    // Clear the previous run's discovery/bootstrap leftovers BEFORE the process launches: a stale
    // handshake file would let the dial loop connect to a dead (possibly reassigned) port, and a
    // stale companion-error would abort a perfectly healthy start.
    CompanionSocketFile.delete(serverDir)
    companionErrorFlag(serverDir).delete()

    val pb = ProcessBuilder(cmd).directory(serverDir).redirectErrorStream(true)

    val proc = pb.start()
    process = proc
    processStdin = proc.outputStream
    companion?.close()
    companion =
        CompanionClient(serverDir, if (protocolLog) ProtocolTee.forServer(serverDir) else null)
    // Reset only after the fresh process is published, so a concurrent health check never pairs
    // the old dead process with a cleared flag.
    stopRequested = false

    Thread(
            {
              proc.inputStream.bufferedReader().forEachLine { line ->
                if (!logSuppressed) ui.serverLog("  ${formatServerLine(line)}")
              }
            },
            "server-$port-output",
        )
        .apply { isDaemon = true }
        .start()
  }

  open fun stop() {
    // Record the intent BEFORE the early returns: a crashed server that cleanup then stop()s
    // must settle to "not unexpected" too.
    stopRequested = true
    // Drop the companion connection first — the graceful path talks to the server over stdin, and
    // an intentionally-closed connection must never be read as a crash by an in-flight await.
    companion?.close()
    companion = null
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

  open fun sendCommand(command: String) {
    processStdin?.let {
      it.write("$command\n".toByteArray())
      it.flush()
    }
  }

  /**
   * Asks the companion to save the world and waits for its `saveComplete` event. The `saving`
   * status message triggers the save (via Bukkit API, no broadcast) and opens the companion's
   * save-protection window; any stale completion from a previous save is drained first so it can't
   * satisfy this wait.
   */
  open fun saveWorld(timeoutMs: Long = SAVE_TIMEOUT_MS): Boolean {
    val client = companion ?: return false
    client.drainSaveCompletions()
    if (!client.sendStatus(CompanionWire.STATE_SAVING)) return false
    return client.awaitSaveComplete(timeoutMs)
  }

  open fun isRunning(): Boolean = process?.isAlive == true

  /**
   * True only when the server process died WITHOUT [stop] having been requested — a crash, OOM
   * kill, or external termination. Health checks must use this instead of `!isRunning()`: modes
   * restart the server on purpose from watcher callbacks (restart-mode rebuilds, hot-reload leak
   * restarts, post-fix restarts), and sampling `isRunning()` during that stop→start window reads an
   * intentional restart as a server death and tears the whole session down.
   */
  open fun hasExitedUnexpectedly(): Boolean {
    val proc = process ?: return false
    return !proc.isAlive && !stopRequested
  }

  /**
   * Waits for the server to be player-ready: dials the companion socket (discovery via the
   * handshake file), then awaits the companion's explicit `ready` event. The two signals are
   * deliberately separate — an established connection proves the companion enabled, and only the
   * streamed event proves the server finished startup. A companion bootstrap failure (the
   * `companion-error` file) is surfaced and aborts immediately rather than waiting out the timeout.
   */
  open fun waitForReady(): Boolean {
    val proc = process ?: return false
    // start() creates the client for real runs; the lazy fallback keeps waitForReady drivable
    // in tests that install a process without going through start().
    val client =
        companion
            ?: CompanionClient(
                    serverDir,
                    if (protocolLog) ProtocolTee.forServer(serverDir) else null,
                )
                .also { companion = it }
    val start = System.currentTimeMillis()
    val outcome =
        client.connect(
            SERVER_READY_TIMEOUT_MS,
            isAlive = { proc.isAlive },
            companionError = { consumeCompanionError() },
        )
    when (outcome) {
      is CompanionClient.ConnectOutcome.CompanionFailed -> {
        ui.error(outcome.message.ifEmpty { "PaperPlane companion failed to start." })
        return false
      }
      CompanionClient.ConnectOutcome.Died,
      CompanionClient.ConnectOutcome.TimedOut -> return false
      CompanionClient.ConnectOutcome.Connected -> {}
    }
    val remaining = SERVER_READY_TIMEOUT_MS - (System.currentTimeMillis() - start)
    return client.awaitServerReady(remaining.coerceAtLeast(0), isAlive = { proc.isAlive })
  }

  /** Reads-and-clears the companion's bootstrap failure message, or null when none exists. */
  private fun consumeCompanionError(): String? {
    val errorFile = companionErrorFlag(serverDir)
    if (!errorFile.exists()) return null
    val message = runCatching { errorFile.readText() }.getOrNull()?.trim().orEmpty()
    errorFile.delete()
    return message
  }

  /**
   * Pushes a build-state update to the companion (chat broadcast + save-protection window on the
   * server side). Best-effort: with no live connection — server down, or the state is an error
   * being reported while nothing is running — the update is dropped, which matches its advisory
   * role.
   */
  open fun sendCompanionStatus(state: String, duration: String? = null, message: String? = null) {
    companion?.sendStatus(state, duration, message)
  }

  /**
   * Sends a [LoadRequest] to the companion host. Best-effort like [sendCompanionStatus] — the
   * caller's [awaitLoadReport] resolves the outcome either way.
   */
  open fun sendLoadRequest(request: LoadRequest): Boolean =
      companion?.sendLoadRequest(request) ?: false

  /**
   * Waits for the companion's report answering [expectedRequestId]. Resolves
   * [LoadWaitResult.ServerExited] when the process dies or the connection drops (no client at all —
   * the server was never started — counts as exited too).
   */
  internal open fun awaitLoadReport(expectedRequestId: String, timeoutMs: Long): LoadWaitResult =
      companion?.awaitReport(expectedRequestId, timeoutMs, isAlive = ::isRunning)
          ?: LoadWaitResult.ServerExited
}

package dev.paperplane.cli.server

import com.charleskorn.kaml.YamlMap
import dev.paperplane.cli.config.DevConfig
import dev.paperplane.cli.config.ServerConfig
import dev.paperplane.cli.plugins.atomicMoveOrFallback
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

open class PaperServerManager(
    val serverDir: File,
    private val downloader: PaperDownloader,
    private val ui: TerminalUI,
    private val port: Int = DEFAULT_PORT,
) {
  companion object {
    internal const val DEFAULT_PORT = 25565
    private const val GRACEFUL_STOP_TIMEOUT_SECONDS = 30L
    private const val SIGTERM_TIMEOUT_SECONDS = 5L
    private const val FORCE_STOP_TIMEOUT_SECONDS = 2L
    private const val SAVE_POLL_INTERVAL_MS = 200L
    /** Highest vanilla op permission level — full command access on the dev server. */
    private const val OP_PERMISSION_LEVEL = 4
    private const val SERVER_READY_TIMEOUT_MS = 120_000L
    private const val READY_POLL_INTERVAL_MS = 100L
    /** Schema version of `.paperplane/companion-config.json`; bumped if its shape changes. */
    private const val COMPANION_CONFIG_VERSION = 1

    /**
     * The flag the companion writes when it fails to enable. Read by [waitForReady]; cleared by the
     * CLI's stale-flag hygiene before each server start.
     */
    internal fun companionErrorFlag(serverDir: File): File =
        File(serverDir, ".paperplane/companion-error")
  }

  private var process: Process? = null
  private var processStdin: java.io.OutputStream? = null
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

  /**
   * Writes the companion's runtime config to `.paperplane/companion-config.json` — currently just
   * the leak-diagnostics mode, with a `protocolVersion` so the companion can reject shapes it
   * doesn't understand. Called on every start (harmless in restart/blue-green: the natively-loaded
   * companion simply doesn't act on it, but shipping it unconditionally means the companion never
   * has to guess). Written atomically (tmp + move) so the companion never reads a torn document.
   */
  open fun writeCompanionConfig(dev: DevConfig) {
    val statusDir = File(serverDir, ".paperplane").apply { mkdirs() }
    val payload =
        mapOf(
            "protocolVersion" to COMPANION_CONFIG_VERSION,
            "leakDiagnostics" to dev.leakDiagnostics.name.lowercase(),
        )
    val target = File(statusDir, "companion-config.json")
    val temp = File(statusDir, ".companion-config.json.tmp")
    temp.writeText(gson.toJson(payload))
    atomicMoveOrFallback(temp.toPath(), target.toPath())
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

    val pb = ProcessBuilder(cmd).directory(serverDir).redirectErrorStream(true)

    val proc = pb.start()
    process = proc
    processStdin = proc.outputStream

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
   * Waits for the companion plugin to complete a save. The CLI writes "saving" to
   * companion-status.json, the companion does the save via Bukkit API (no broadcast), then writes a
   * flag file.
   */
  open fun waitForSave(timeoutMs: Long = 10_000): Boolean {
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

  open fun isRunning(): Boolean = process?.isAlive == true

  open fun waitForReady(): Boolean {
    val proc = process ?: return false
    val flagFile = File(serverDir, ".paperplane/server-ready")
    val errorFile = companionErrorFlag(serverDir)
    flagFile.delete() // Clear stale flag
    val startTime = System.currentTimeMillis()
    val timeout = SERVER_READY_TIMEOUT_MS
    while (proc.isAlive && System.currentTimeMillis() - startTime < timeout) {
      if (flagFile.exists()) {
        flagFile.delete()
        return true
      }
      // The companion writes this if it fails to enable (e.g. unsupported Paper). Surface it and
      // abort immediately rather than waiting out the full ready timeout.
      if (errorFile.exists()) {
        val message = runCatching { errorFile.readText() }.getOrNull()?.trim().orEmpty()
        errorFile.delete()
        ui.error(message.ifEmpty { "PaperPlane companion failed to start." })
        return false
      }
      Thread.sleep(READY_POLL_INTERVAL_MS)
    }
    return false
  }

  open fun writeCompanionStatus(state: String, extra: Map<String, Any> = emptyMap()) {
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
}

package dev.paperplane.cli.server

import dev.paperplane.cli.ui.TerminalUI
import java.io.File

class PaperServerManager(
    val serverDir: File,
    private val downloader: PaperDownloader,
    private val port: Int = 25565
) {
    private var process: Process? = null
    private var processStdin: java.io.OutputStream? = null
    private val pluginsDir = File(serverDir, "plugins")
    private val gson = com.google.gson.Gson()

    /**
     * Cleans up stale state from a previous run that wasn't shut down cleanly.
     * Kills any process occupying the server port and removes world lock files.
     */
    fun cleanupStale() {
        // Kill any process still bound to our port (zombie from previous run)
        try {
            val lsof = ProcessBuilder("lsof", "-ti", "tcp:$port")
                .redirectErrorStream(true)
                .start()
            val pids = lsof.inputStream.bufferedReader().readText().trim()
            lsof.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (pids.isNotEmpty()) {
                for (pid in pids.lines().filter { it.isNotBlank() }) {
                    ProcessBuilder("kill", "-9", pid).start().waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                }
                Thread.sleep(500) // Brief wait for port release
            }
        } catch (_: Exception) {
            // Best-effort; lsof may not be available on all systems
        }

        // Remove stale session.lock files from world directories
        if (serverDir.exists()) {
            serverDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val lock = File(dir, "session.lock")
                if (lock.exists()) lock.delete()
            }
        }
    }

    fun configure() {
        serverDir.mkdirs()
        pluginsDir.mkdirs()

        writeIfMissing("eula.txt", "eula=true\n")

        // Always overwrite — PaperPlane manages these settings, and Paper rewrites
        // the file on first boot (making writeIfMissing a no-op for new properties)
        File(serverDir, "server.properties").writeText("""
            online-mode=false
            view-distance=4
            simulation-distance=4
            level-type=flat
            spawn-protection=0
            max-players=2
            enable-command-block=true
            server-port=$port
            motd=PaperPlane Dev Server
            generate-structures=false
            accepts-transfers=true
        """.trimIndent() + "\n")

        writeIfMissing("bukkit.yml", """
            settings:
              allow-end: false
              connection-throttle: 0
            ticks-per:
              autosave: 0
        """.trimIndent() + "\n")

        writeIfMissing("spigot.yml", """
            settings:
              save-user-cache-on-stop-only: true
              bungeecord: false
            world-settings:
              default:
                verbose: false
        """.trimIndent() + "\n")

        // Paper config — only non-gameplay-affecting optimizations
        val paperConfigDir = File(serverDir, "config")
        paperConfigDir.mkdirs()
        writeIfMissing(File(paperConfigDir, "paper-global.yml"), """
            timings:
              enabled: false
        """.trimIndent() + "\n")

        writeIfMissing(File(paperConfigDir, "paper-world-defaults.yml"), """
            chunks:
              auto-save-interval: -1
            spawn:
              keep-spawn-loaded: false
        """.trimIndent() + "\n")
    }

    fun configureVelocityForwarding(secret: String) {
        val paperConfigDir = File(serverDir, "config")
        paperConfigDir.mkdirs()
        // Always overwrite paper-global.yml when proxy is enabled to ensure velocity settings are correct
        File(paperConfigDir, "paper-global.yml").writeText("""
            proxies:
              velocity:
                enabled: true
                online-mode: true
                secret: "$secret"
            timings:
              enabled: false
        """.trimIndent() + "\n")
    }

    fun downloadServer(mcVersion: String): File {
        return downloader.download(mcVersion)
    }

    fun copyPlugin(jarPath: File) {
        val target = File(pluginsDir, jarPath.name)
        val temp = File(pluginsDir, ".${jarPath.name}.tmp")
        jarPath.copyTo(temp, overwrite = true)
        try {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            temp.renameTo(target)
        }
    }

    /**
     * Stages a plugin jar in .paperplane/ (not plugins/) so Paper doesn't delete it.
     * Used in hot-reload mode so the companion can roll back to the original on failure.
     * Returns the absolute path to the staged file.
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
     * Extracts the PaperPlane Java agent JAR from CLI resources.
     * Used for HMR Level 2 (instrumentation-based hot-swap).
     */
    fun extractAgent(): File {
        val agentJar = File(serverDir, ".paperplane/paperplane-agent.jar")
        if (agentJar.exists()) return agentJar
        agentJar.parentFile.mkdirs()
        extractResource("paperplane-agent.bin", agentJar)
        return agentJar
    }

    private fun extractResource(resourceName: String, target: File) {
        val stream = javaClass.classLoader.getResourceAsStream(resourceName)
            ?: throw RuntimeException("Resource '$resourceName' not found in CLI jar — corrupted build?")
        stream.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    fun start(paperJar: File, jvmArgs: List<String>, hotReload: Boolean = false, javaBin: String = "java"): Process {
        val cmd = mutableListOf(javaBin)
        // Fast startup flags
        cmd.addAll(listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-XX:+UseG1GC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+DisableExplicitGC",
            "-XX:InitiatingHeapOccupancyPercent=75"
        ))

        if (hotReload) {
            val agentJar = extractAgent()
            cmd.add("-javaagent:${agentJar.absolutePath}")
        }

        cmd.addAll(jvmArgs)
        cmd.addAll(listOf("-jar", paperJar.absolutePath, "--nogui"))

        val pb = ProcessBuilder(cmd)
            .directory(serverDir)
            .redirectErrorStream(true)

        val proc = pb.start()
        process = proc
        processStdin = proc.outputStream

        Thread({
            proc.inputStream.bufferedReader().forEachLine { line ->
                TerminalUI.serverLog("  ${formatServerLine(line)}")
            }
        }, "server-$port-output").apply { isDaemon = true }.start()

        return proc
    }

    fun stop() {
        val proc = process ?: return
        if (!proc.isAlive) return

        proc.destroy()
        val exited = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        if (!exited) {
            proc.destroyForcibly()
            proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
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
     * Waits for the companion plugin to complete a save.
     * The CLI writes "saving" to companion-status.json, the companion does the save
     * via Bukkit API (no broadcast), then writes a flag file.
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
            Thread.sleep(200)
        }
        return false
    }

    fun isRunning(): Boolean = process?.isAlive == true

    fun waitForReady(): Boolean {
        val proc = process ?: return false
        val flagFile = File(serverDir, ".paperplane/server-ready")
        flagFile.delete() // Clear stale flag
        val startTime = System.currentTimeMillis()
        val timeout = 120_000L
        while (proc.isAlive && System.currentTimeMillis() - startTime < timeout) {
            if (flagFile.exists()) {
                flagFile.delete()
                return true
            }
            Thread.sleep(100)
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
        tmpFile.renameTo(statusFile)
    }

    private val serverLineRegex = Regex("""\[[\d:]+] \[([^]]+)] (.+)""")

    private fun formatServerLine(line: String): String {
        val match = serverLineRegex.find(line)
        return if (match != null) {
            val (thread, message) = match.destructured
            "\u001b[2m[$thread]\u001b[0m $message"
        } else {
            line
        }
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

package dev.paperplane.cli.server

import dev.paperplane.cli.ui.TerminalUI
import java.io.File

class PaperServerManager(
    val serverDir: File,
    private val downloader: PaperDownloader,
    var verboseServer: Boolean = false,
    private val port: Int = 25565
) {
    private var process: Process? = null
    private var processStdin: java.io.OutputStream? = null
    private val pluginsDir = File(serverDir, "plugins")

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
     * Stages a plugin jar as a .new file without overwriting the currently-loaded jar.
     * Used in hot-reload mode so the companion can roll back to the original on failure.
     * Returns the staged filename (e.g., "myplugin.jar.new").
     */
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
        val companionStream = javaClass.classLoader.getResourceAsStream("paperplane-companion.bin")
        if (companionStream != null) {
            val target = File(pluginsDir, "paperplane-companion.jar")
            companionStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Remove stale jar from before the overlay → companion rename
            File(pluginsDir, "paperplane-overlay.jar").delete()
        }
    }

    fun start(paperJar: File, jvmArgs: List<String>): Process {
        val cmd = mutableListOf("java")
        // Fast startup flags
        cmd.addAll(listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-XX:+UseG1GC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+DisableExplicitGC",
            "-XX:InitiatingHeapOccupancyPercent=75"
        ))
        cmd.addAll(jvmArgs)
        cmd.addAll(listOf("-jar", paperJar.absolutePath, "--nogui"))

        val pb = ProcessBuilder(cmd)
            .directory(serverDir)
            .redirectErrorStream(true)

        val proc = pb.start()
        process = proc
        processStdin = proc.outputStream

        // Stream server output, filtering for relevant lines
        Thread({
            proc.inputStream.bufferedReader().forEachLine { line ->
                if (shouldShowLine(line)) {
                    println("  ${formatServerLine(line)}")
                }
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
     * The CLI writes "saving" to overlay-status.json, the companion does the save
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
        val timeout = 60_000L
        while (proc.isAlive && System.currentTimeMillis() - startTime < timeout) {
            // Prefer flag file (written by companion plugin after all plugins loaded)
            if (flagFile.exists()) {
                flagFile.delete()
                return true
            }
            // Fallback: TCP port check (works even without companion)
            try {
                java.net.Socket("localhost", port).close()
                return true
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        return false
    }

    fun writeOverlayStatus(state: String, extra: Map<String, String> = emptyMap()) {
        val statusFile = File(serverDir, ".paperplane/overlay-status.json")
        statusFile.parentFile.mkdirs()
        val json = buildString {
            append("{\"state\":\"$state\"")
            for ((k, v) in extra) {
                append(",\"$k\":\"$v\"")
            }
            append("}")
        }
        statusFile.writeText(json)
    }

    private fun shouldShowLine(line: String): Boolean {
        if (verboseServer) return true
        if (line.startsWith("WARNING:")) return false // JVM native access warnings

        // Only show real errors — skip known harmless ones
        if (line.contains("No key layers in MapLike")) return false
        if (line.contains("ERROR")) return true
        if (line.contains("WARN") && line.contains("plugin", ignoreCase = true)) return true

        return false
    }

    private fun formatServerLine(line: String): String {
        // Dim the timestamp/thread prefix, keep the message
        val match = Regex("""\[[\d:]+] \[([^]]+)] (.+)""").find(line)
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

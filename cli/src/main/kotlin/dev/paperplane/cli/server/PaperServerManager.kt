package dev.paperplane.cli.server

import dev.paperplane.cli.ui.TerminalUI
import java.io.File

class PaperServerManager(
    private val serverDir: File,
    private val downloader: PaperDownloader,
    var verboseServer: Boolean = false
) {
    private var process: Process? = null
    private val pluginsDir = File(serverDir, "plugins")

    fun configure() {
        serverDir.mkdirs()
        pluginsDir.mkdirs()

        writeIfMissing("eula.txt", "eula=true\n")

        writeIfMissing("server.properties", """
            online-mode=false
            view-distance=4
            simulation-distance=4
            level-type=flat
            spawn-protection=0
            max-players=2
            enable-command-block=true
            server-port=25565
            motd=PaperPlane Dev Server
            generate-structures=false
        """.trimIndent() + "\n")

        writeIfMissing("bukkit.yml", """
            settings:
              allow-end: false
              connection-throttle: 0
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

    fun downloadServer(mcVersion: String): File {
        return downloader.download(mcVersion)
    }

    fun copyPlugin(jarPath: File) {
        val target = File(pluginsDir, jarPath.name)
        jarPath.copyTo(target, overwrite = true)
    }

    fun copyOverlay() {
        val overlayStream = javaClass.classLoader.getResourceAsStream("paperplane-overlay.bin")
        if (overlayStream != null) {
            val target = File(pluginsDir, "paperplane-overlay.jar")
            overlayStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    fun start(paperJar: File, jvmArgs: List<String>): Process {
        val cmd = mutableListOf("java")
        // Fast startup flags
        cmd.addAll(listOf(
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

        // Stream server output, filtering for relevant lines
        Thread({
            proc.inputStream.bufferedReader().forEachLine { line ->
                if (shouldShowLine(line)) {
                    println("  ${formatServerLine(line)}")
                }
            }
        }, "server-output").apply { isDaemon = true }.start()

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
    }

    fun isRunning(): Boolean = process?.isAlive == true

    fun waitForReady(): Boolean {
        val proc = process ?: return false
        // Wait for "Done" message in server output by polling a marker file
        // Or we detect via the server output thread
        // For simplicity, wait for the server process to be alive and port to be available
        val startTime = System.currentTimeMillis()
        val timeout = 60_000L
        while (proc.isAlive && System.currentTimeMillis() - startTime < timeout) {
            try {
                java.net.Socket("localhost", 25565).close()
                return true
            } catch (_: Exception) {
                Thread.sleep(500)
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

        // Suppress known server noise
        if (isServerNoise(line)) return false

        // Show plugin-related output and real errors
        if (line.contains("Done (")) return false  // redundant, we show "server ready"
        if (line.contains("Starting minecraft server")) return false
        if (line.contains("ERROR") && !isServerNoise(line)) return true
        if (line.contains("[Server thread/INFO]")) {
            return !line.contains("UUID of player") &&
                !line.contains("moved too quickly") &&
                !line.contains("lost connection") &&
                !line.contains("Preparing") &&
                !line.contains("Time elapsed")
        }
        return false
    }

    private fun isServerNoise(line: String): Boolean {
        return line.contains("Advanced terminal features are not available") ||
            line.contains("sun.misc.Unsafe") ||
            line.contains("terminally deprecated") ||
            line.contains("Please consider reporting this to the maintainers") ||
            line.contains("will be removed in a future release") ||
            line.contains("No key layers in MapLike") ||
            line.contains("RUNNING IN OFFLINE/INSECURE MODE") ||
            line.contains("make no attempt to authenticate") ||
            line.contains("opens up the ability for hackers") ||
            line.contains("set \"online-mode\" to \"true\"") ||
            line.contains("didn't have a version set") ||
            line.contains("ServerMain WARN") ||
            line.contains("Loading server properties") ||
            line.contains("Default game type") ||
            line.contains("Generating keypair") ||
            line.contains("Preparing level") ||
            line.contains("Preparing start region") ||
            line.contains("Environment:") ||
            line.startsWith("WARNING:")
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

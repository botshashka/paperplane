package dev.paperplane.cli.server

import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import java.util.UUID

class VelocityManager(
    private val proxyDir: File,
    var verboseProxy: Boolean = false
) {
    private var process: Process? = null
    private val pluginsDir = File(proxyDir, "plugins")

    val forwardingSecret: String = UUID.randomUUID().toString()

    fun configure(serverPort: Int = 25566, swapPort: Int = 25567, proxyPort: Int = 25565) {
        proxyDir.mkdirs()
        pluginsDir.mkdirs()

        // Always overwrite — generated config, needs both backends registered
        File(proxyDir, "velocity.toml").writeText("""
            # PaperPlane Velocity proxy config
            bind = "0.0.0.0:$proxyPort"
            motd = "PaperPlane Dev Server"
            show-max-players = 2
            online-mode = false
            player-info-forwarding-mode = "modern"
            announce-forge = false

            [servers]
            server = "127.0.0.1:$serverPort"
            swap = "127.0.0.1:$swapPort"
            try = ["server", "swap"]

            [forced-hosts]

            [advanced]
            compression-threshold = 256
            compression-level = -1
            login-ratelimit = 0
            connection-timeout = 5000
            read-timeout = 30000
            haproxy-protocol = false
            tcp-fast-open = false
            bungee-plugin-message-channel = true
            show-ping-requests = false
            failover-on-unexpected-server-disconnect = true

            [query]
            enabled = false
            port = 25577
        """.trimIndent() + "\n")

        // Write forwarding secret
        File(proxyDir, "forwarding.secret").writeText(forwardingSecret)

        // Write initial active server state
        writeActiveServer("blue")

        // Copy embedded transfer plugin
        copyTransferPlugin()
    }

    fun clearTransferComplete() {
        File(proxyDir, "transfer-complete").delete()
    }

    fun waitForTransferComplete(timeoutMs: Long = 5000): Boolean {
        val file = File(proxyDir, "transfer-complete")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (file.exists()) {
                file.delete()
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    fun writeActiveServer(serverName: String, transfer: Boolean = false) {
        File(proxyDir, "active-server.json").writeText(
            """{"active":"$serverName","transfer":$transfer}"""
        )
    }

    fun start(velocityJar: File): Process {
        val cmd = listOf(
            "java",
            "-Xmx256M",
            "-XX:+UseG1GC",
            "--enable-native-access=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
            "--add-opens", "java.base/java.io=ALL-UNNAMED",
            "-jar", velocityJar.absolutePath
        )

        val pb = ProcessBuilder(cmd)
            .directory(proxyDir)
            .redirectErrorStream(true)

        val proc = pb.start()
        process = proc

        Thread({
            proc.inputStream.bufferedReader().forEachLine { line ->
                if (shouldShowLine(line)) {
                    TerminalUI.serverLog("  ${formatProxyLine(line)}")
                }
            }
        }, "proxy-output").apply { isDaemon = true }.start()

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

    fun waitForReady(port: Int = 25565): Boolean {
        val proc = process ?: return false
        val startTime = System.currentTimeMillis()
        val timeout = 30_000L
        while (proc.isAlive && System.currentTimeMillis() - startTime < timeout) {
            try {
                java.net.Socket("localhost", port).close()
                return true
            } catch (_: Exception) {
                Thread.sleep(500)
            }
        }
        return false
    }

    private fun copyTransferPlugin() {
        val stream = javaClass.classLoader.getResourceAsStream("paperplane-velocity.bin")
        if (stream != null) {
            val target = File(pluginsDir, "paperplane-transfer.jar")
            stream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun shouldShowLine(line: String): Boolean {
        if (verboseProxy) return true
        if (line.startsWith("WARNING:")) return false
        if (line.contains("[ERROR]")) return true
        return false
    }

    private fun formatProxyLine(line: String): String {
        val match = Regex("""\[[\d:]+] \[([^]]+)] (.+)""").find(line)
        return if (match != null) {
            val (thread, message) = match.destructured
            "\u001b[2m[proxy/$thread]\u001b[0m $message"
        } else {
            "\u001b[2m[proxy]\u001b[0m $line"
        }
    }
}

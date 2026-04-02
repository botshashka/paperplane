package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.server.PaperDownloader
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.server.VelocityDownloader
import dev.paperplane.cli.server.VelocityManager
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.watcher.FileWatcher
import java.io.File

class DevCommand : CliktCommand(name = "dev") {
    private val verbose by option("--verbose", "-v", help = "Show all server output").flag()
    private val projectDir = File(System.getProperty("user.dir"))

    override fun run() {
        val version = javaClass.`package`?.implementationVersion ?: "0.1.0"
        TerminalUI.header(version)

        // Load config
        val config = PaperPlaneConfig.load(projectDir)
        val ppDir = File(projectDir, ".paperplane")
        ppDir.mkdirs()

        val useProxy = config.dev.proxy
        val paperPort = if (useProxy) 25566 else 25565
        val proxyPort = 25565

        val gradle = GradleBridge(projectDir)
        val downloader = PaperDownloader(File(ppDir, "cache"))
        val serverManager = PaperServerManager(File(ppDir, "server"), downloader, config.dev.verboseServer, paperPort)

        val velocityDownloader = if (useProxy) VelocityDownloader(File(ppDir, "cache")) else null
        val velocityManager = if (useProxy) VelocityManager(File(ppDir, "proxy")) else null

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread {
            serverManager.stop()
            velocityManager?.stop()
            gradle.close()
        })

        // Step 1: Get metadata
        val metadata = TerminalUI.spin("Reading project metadata...") {
            gradle.metadata()
        }
        if (metadata == null) {
            TerminalUI.error("Failed to read project metadata. Is the PaperPlane Gradle plugin applied?")
            return
        }

        // Step 2: Build
        val buildStart = System.currentTimeMillis()
        serverManager.writeOverlayStatus("building")
        val buildSuccess = TerminalUI.spin("Building...") {
            gradle.build()
        }
        val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

        if (!buildSuccess) {
            TerminalUI.error("Build failed", buildDuration)
            serverManager.writeOverlayStatus("error", mapOf("message" to "Build failed"))
            TerminalUI.blank()
            TerminalUI.status("Waiting for changes...")
            waitForFixAndRestart(gradle, serverManager, velocityManager, velocityDownloader, config, metadata.jarPath, ppDir)
            return
        }
        TerminalUI.success("Build succeeded", buildDuration)

        // Step 3: Download Paper if needed
        val mcVersion = config.server.version ?: metadata.paperApiVersion
        val paperJar = TerminalUI.spin("Downloading Paper $mcVersion...") {
            downloader.download(mcVersion)
        }

        // Step 4: Start Velocity proxy if enabled
        if (useProxy && velocityManager != null && velocityDownloader != null) {
            val velocityJar = TerminalUI.spin("Downloading Velocity...") {
                velocityDownloader.download()
            }

            velocityManager.configure(backendPort = paperPort, proxyPort = proxyPort)

            val proxyStart = System.currentTimeMillis()
            velocityManager.start(velocityJar)
            val proxyReady = TerminalUI.spin("Starting Velocity proxy...") {
                velocityManager.waitForReady(proxyPort)
            }
            val proxyDuration = formatDuration(System.currentTimeMillis() - proxyStart)

            if (proxyReady) {
                TerminalUI.success("Velocity proxy ready", proxyDuration)
            } else {
                TerminalUI.error("Proxy failed to start", proxyDuration)
                return
            }
        }

        // Step 5: Configure server
        serverManager.configure()
        if (useProxy && velocityManager != null) {
            serverManager.configureVelocityForwarding(velocityManager.forwardingSecret)
        }

        // Step 6: Copy plugin + overlay
        val builtJar = File(projectDir, metadata.jarPath)
        serverManager.copyPlugin(builtJar)
        if (config.dev.overlay) {
            serverManager.copyOverlay()
        }

        // Step 7: Start server
        val serverStart = System.currentTimeMillis()
        serverManager.start(paperJar, config.server.jvmArgs)
        val ready = TerminalUI.spin("Starting Paper $mcVersion server...") {
            serverManager.waitForReady()
        }
        val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)

        if (ready) {
            TerminalUI.success("Paper $mcVersion server ready", serverDuration)
            serverManager.writeOverlayStatus("ready", mapOf("duration" to serverDuration))
        } else {
            TerminalUI.error("Server failed to start", serverDuration)
            serverManager.writeOverlayStatus("error", mapOf("message" to "Server failed to start"))
            return
        }

        TerminalUI.blank()
        if (useProxy) {
            TerminalUI.info("Server:", "localhost:$proxyPort (via proxy)")
        } else {
            TerminalUI.info("Server:", "localhost:$paperPort")
        }
        TerminalUI.info("Plugin:", "${metadata.pluginName} v${metadata.version}")
        if (config.dev.overlay) {
            TerminalUI.info("Overlay:", "enabled")
        }
        if (useProxy) {
            TerminalUI.info("Proxy:", "enabled (hot reload)")
        }
        TerminalUI.blank()
        TerminalUI.status("Watching for changes...")

        // Step 8: Watch and rebuild loop
        val srcDir = File(projectDir, "src")
        val watcher = FileWatcher(srcDir, config.dev.debounceMs) { changedFiles ->
            val shortName = changedFiles.firstOrNull()
                ?.substringAfterLast("/")
                ?: "files"
            val extra = if (changedFiles.size > 1) " (+${changedFiles.size - 1} more)" else ""
            TerminalUI.change("Change detected: $shortName$extra")

            rebuildAndRestart(gradle, serverManager, config, metadata, paperJar, projectDir)
        }

        watcher.start()

        // Block until interrupted
        try {
            while (true) {
                Thread.sleep(1000)
                // In proxy mode, only Paper dying outside rebuild is non-fatal (proxy holds connection)
                // Velocity dying is fatal
                if (useProxy && velocityManager != null) {
                    if (!velocityManager.isRunning()) {
                        TerminalUI.error("Proxy process exited unexpectedly")
                        break
                    }
                } else {
                    if (!serverManager.isRunning()) {
                        TerminalUI.error("Server process exited unexpectedly")
                        break
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Ctrl+C
        } finally {
            watcher.stop()
            serverManager.stop()
            velocityManager?.stop()
            gradle.close()
            TerminalUI.blank()
            TerminalUI.status("Goodbye!")
        }
    }

    private fun rebuildAndRestart(
        gradle: GradleBridge,
        serverManager: PaperServerManager,
        config: PaperPlaneConfig,
        metadata: dev.paperplane.cli.gradle.ProjectMetadata,
        paperJar: File,
        projectDir: File
    ) {
        val totalStart = System.currentTimeMillis()

        // Stop server (proxy stays running if enabled)
        serverManager.stop()
        serverManager.writeOverlayStatus("building")

        // Rebuild
        val buildStart = System.currentTimeMillis()
        val buildSuccess = gradle.build()
        val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

        if (!buildSuccess) {
            TerminalUI.error("Build failed", buildDuration)
            serverManager.writeOverlayStatus("error", mapOf("message" to "Build failed"))
            TerminalUI.blank()
            TerminalUI.status("Waiting for changes...")
            return
        }
        TerminalUI.success("Build succeeded", buildDuration)

        // Copy updated jar
        val builtJar = File(projectDir, metadata.jarPath)
        serverManager.copyPlugin(builtJar)

        // Restart server
        val serverStart = System.currentTimeMillis()
        serverManager.start(paperJar, config.server.jvmArgs)
        val ready = serverManager.waitForReady()
        val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)

        if (ready) {
            TerminalUI.success("Server ready", serverDuration)
            val totalDuration = formatDuration(System.currentTimeMillis() - totalStart)
            TerminalUI.totalTime(totalDuration)
            serverManager.writeOverlayStatus("ready", mapOf("duration" to totalDuration))
        } else {
            TerminalUI.error("Server failed to start", serverDuration)
            serverManager.writeOverlayStatus("error", mapOf("message" to "Server failed to start"))
        }
    }

    private fun waitForFixAndRestart(
        gradle: GradleBridge,
        serverManager: PaperServerManager,
        velocityManager: VelocityManager?,
        velocityDownloader: VelocityDownloader?,
        config: PaperPlaneConfig,
        jarPath: String,
        ppDir: File
    ) {
        // Wait for file changes, then try building again
        val srcDir = File(projectDir, "src")
        val watcher = FileWatcher(srcDir, config.dev.debounceMs) { changedFiles ->
            val shortName = changedFiles.firstOrNull()?.substringAfterLast("/") ?: "files"
            TerminalUI.change("Change detected: $shortName")

            val buildStart = System.currentTimeMillis()
            serverManager.writeOverlayStatus("building")
            val buildSuccess = gradle.build()
            val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

            if (buildSuccess) {
                TerminalUI.success("Build succeeded", buildDuration)
                // Now do the full server startup
                val metadata = gradle.metadata() ?: return@FileWatcher
                val mcVersion = config.server.version ?: metadata.paperApiVersion
                val downloader = PaperDownloader(File(ppDir, "cache"))
                val paperJar = downloader.download(mcVersion)
                serverManager.configure()

                // Start proxy if enabled and not already running
                if (config.dev.proxy && velocityManager != null && velocityDownloader != null && !velocityManager.isRunning()) {
                    val velocityJar = velocityDownloader.download()
                    velocityManager.configure(backendPort = 25566, proxyPort = 25565)
                    velocityManager.start(velocityJar)
                    velocityManager.waitForReady(25565)
                    serverManager.configureVelocityForwarding(velocityManager.forwardingSecret)
                }

                val builtJar = File(projectDir, metadata.jarPath)
                serverManager.copyPlugin(builtJar)
                if (config.dev.overlay) serverManager.copyOverlay()

                val serverStart = System.currentTimeMillis()
                serverManager.start(paperJar, config.server.jvmArgs)
                val ready = serverManager.waitForReady()
                val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)
                if (ready) {
                    TerminalUI.success("Server ready", serverDuration)
                    serverManager.writeOverlayStatus("ready", mapOf("duration" to serverDuration))
                }
            } else {
                TerminalUI.error("Build failed", buildDuration)
                TerminalUI.blank()
                TerminalUI.status("Waiting for changes...")
            }
        }
        watcher.start()

        try {
            while (true) Thread.sleep(1000)
        } catch (_: InterruptedException) {
            watcher.stop()
            serverManager.stop()
            velocityManager?.stop()
            gradle.close()
        }
    }

    private fun formatDuration(ms: Long): String {
        return if (ms >= 1000) "%.1fs".format(ms / 1000.0) else "${ms}ms"
    }
}

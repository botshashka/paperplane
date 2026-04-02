package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.server.PaperDownloader
import dev.paperplane.cli.server.PaperServerManager
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

        val gradle = GradleBridge(projectDir)
        val downloader = PaperDownloader(File(ppDir, "cache"))
        val serverManager = PaperServerManager(File(ppDir, "server"), downloader, config.dev.verboseServer)

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread {
            serverManager.stop()
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
            waitForFixAndRestart(gradle, serverManager, config, metadata.jarPath, ppDir)
            return
        }
        TerminalUI.success("Build succeeded", buildDuration)

        // Step 3: Download Paper if needed
        val mcVersion = config.server.version ?: metadata.paperApiVersion
        val paperJar = TerminalUI.spin("Downloading Paper $mcVersion...") {
            downloader.download(mcVersion)
        }

        // Step 4: Configure server
        serverManager.configure()

        // Step 5: Copy plugin + overlay
        val builtJar = File(projectDir, metadata.jarPath)
        serverManager.copyPlugin(builtJar)
        if (config.dev.overlay) {
            serverManager.copyOverlay()
        }

        // Step 6: Start server
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
        TerminalUI.info("Server:", "localhost:25565")
        TerminalUI.info("Plugin:", "${metadata.pluginName} v${metadata.version}")
        if (config.dev.overlay) {
            TerminalUI.info("Overlay:", "enabled")
        }
        TerminalUI.blank()
        TerminalUI.status("Watching for changes...")

        // Step 7: Watch and rebuild loop
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
                if (!serverManager.isRunning()) {
                    TerminalUI.error("Server process exited unexpectedly")
                    break
                }
            }
        } catch (_: InterruptedException) {
            // Ctrl+C
        } finally {
            watcher.stop()
            serverManager.stop()
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

        // Stop server
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
            gradle.close()
        }
    }

    private fun formatDuration(ms: Long): String {
        return if (ms >= 1000) "%.1fs".format(ms / 1000.0) else "${ms}ms"
    }
}

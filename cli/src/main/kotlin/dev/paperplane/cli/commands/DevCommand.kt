package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.server.PaperDownloader
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.server.ServerSync
import dev.paperplane.cli.server.VelocityDownloader
import dev.paperplane.cli.server.VelocityManager
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.watcher.FileWatcher
import java.io.File

class DevCommand : CliktCommand(name = "dev") {
    private val verbose by option("--verbose", "-v", help = "Show all server output").flag()
    private val hotReload by option("--hot-reload", "-r", help = "Hot-reload plugin without server restart").flag()
    private val projectDir = File(System.getProperty("user.dir"))

    private enum class Slot(val serverName: String, val port: Int) {
        BLUE("blue", 25566),
        GREEN("green", 25567);
        fun other() = if (this == BLUE) GREEN else BLUE
    }

    override fun run() {
        val version = javaClass.`package`?.implementationVersion ?: "0.1.0"
        TerminalUI.header(version)

        val baseConfig = PaperPlaneConfig.load(projectDir)
        // CLI flag overrides config
        val config = if (hotReload) baseConfig.copy(dev = baseConfig.dev.copy(hotReload = true)) else baseConfig
        val ppDir = File(projectDir, ".paperplane")
        ppDir.mkdirs()

        val gradle = GradleBridge(projectDir)
        val downloader = PaperDownloader(File(ppDir, "cache"))

        if (config.dev.hotReload) {
            if (config.dev.proxy) {
                TerminalUI.info("Note:", "hot-reload mode ignores proxy setting (single server only)")
            }
            runSingleServer(config, ppDir, gradle, downloader)
        } else if (config.dev.proxy) {
            runBlueGreen(config, ppDir, gradle, downloader)
        } else {
            runSingleServer(config, ppDir, gradle, downloader)
        }
    }

    // ── Blue/Green mode ─────────────────────────────────────────────────

    private fun runBlueGreen(
        config: PaperPlaneConfig,
        ppDir: File,
        gradle: GradleBridge,
        downloader: PaperDownloader
    ) {
        // Migrate old single-server directory to blue
        val oldServerDir = File(ppDir, "server")
        val blueServerDir = File(ppDir, "server-blue")
        if (oldServerDir.exists() && !blueServerDir.exists()) {
            oldServerDir.renameTo(blueServerDir)
        }

        val servers = mapOf(
            Slot.BLUE to PaperServerManager(File(ppDir, "server-blue"), downloader, config.dev.verboseServer, Slot.BLUE.port),
            Slot.GREEN to PaperServerManager(File(ppDir, "server-green"), downloader, config.dev.verboseServer, Slot.GREEN.port)
        )
        var activeSlot = Slot.BLUE

        val velocityDownloader = VelocityDownloader(File(ppDir, "cache"))
        val velocityManager = VelocityManager(File(ppDir, "proxy"))

        // Shutdown hook
        val shuttingDown = java.util.concurrent.atomic.AtomicBoolean(false)
        Runtime.getRuntime().addShutdownHook(Thread {
            shuttingDown.set(true)
            println() // Clear ^C line
            servers.values.forEach { it.stop() }
            velocityManager.stop()
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
        val active = servers[activeSlot]!!
        active.writeOverlayStatus("building")
        val buildSuccess = TerminalUI.spin("Building...") {
            gradle.build()
        }
        val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

        if (!buildSuccess) {
            TerminalUI.error("Build failed", buildDuration)
            active.writeOverlayStatus("error", mapOf("message" to "Build failed"))
            TerminalUI.blank()
            TerminalUI.status("Waiting for changes...")
            waitForFixAndRestart(gradle, servers, velocityManager, velocityDownloader, config, ppDir)
            return
        }
        TerminalUI.success("Build succeeded", buildDuration)

        // Step 3: Download Paper + Velocity
        val mcVersion = config.server.version ?: metadata.paperApiVersion
        val paperJar = TerminalUI.spin("Downloading Paper $mcVersion...") {
            downloader.download(mcVersion)
        }
        val velocityJar = TerminalUI.spin("Downloading Velocity...") {
            velocityDownloader.download()
        }

        // Step 4: Start Velocity proxy with both backends
        velocityManager.configure(bluePort = Slot.BLUE.port, greenPort = Slot.GREEN.port)

        val proxyStart = System.currentTimeMillis()
        velocityManager.start(velocityJar)
        val proxyReady = TerminalUI.spin("Starting Velocity proxy...") {
            velocityManager.waitForReady(25565)
        }
        val proxyDuration = formatDuration(System.currentTimeMillis() - proxyStart)

        if (proxyReady) {
            TerminalUI.success("Velocity proxy ready", proxyDuration)
        } else {
            TerminalUI.error("Proxy failed to start", proxyDuration)
            return
        }

        // Step 5: Clean up stale state from previous runs, configure and start Blue
        servers.values.forEach { it.cleanupStale() }
        active.configure()
        active.configureVelocityForwarding(velocityManager.forwardingSecret)

        val builtJar = File(projectDir, metadata.jarPath)
        active.copyPlugin(builtJar)
        if (config.dev.companion) active.copyCompanion()

        val serverStart = System.currentTimeMillis()
        active.start(paperJar, config.server.jvmArgs)
        val ready = TerminalUI.spin("Starting Paper $mcVersion server...") {
            active.waitForReady()
        }
        val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)

        if (ready) {
            TerminalUI.success("Paper $mcVersion server ready", serverDuration)
            active.writeOverlayStatus("ready", mapOf("duration" to serverDuration))
        } else {
            TerminalUI.error("Server failed to start", serverDuration)
            active.writeOverlayStatus("error", mapOf("message" to "Server failed to start"))
            return
        }

        TerminalUI.blank()
        TerminalUI.info("Server:", "localhost:25565 (via proxy)")
        TerminalUI.info("Plugin:", "${metadata.pluginName} v${metadata.version}")
        if (config.dev.companion) TerminalUI.info("Companion:", "enabled")
        TerminalUI.info("Mode:", if (config.dev.hotReload) "hot-reload" else "blue/green (zero-downtime)")
        TerminalUI.blank()
        TerminalUI.status("Watching for changes...")

        // Step 6: Pre-warm standby server in background (skip in hot-reload mode)
        if (!config.dev.hotReload) {
            preWarmStandby(servers[Slot.GREEN]!!, active, Slot.GREEN.port, builtJar, config, paperJar, velocityManager)
        }

        // Step 7: Watch and rebuild loop
        val srcDir = File(projectDir, "src")
        val watcher = FileWatcher(srcDir, config.dev.debounceMs) { changedFiles ->
            val shortName = changedFiles.firstOrNull()?.substringAfterLast("/") ?: "files"
            val extra = if (changedFiles.size > 1) " (+${changedFiles.size - 1} more)" else ""
            TerminalUI.change("Change detected: $shortName$extra")

            if (config.dev.hotReload && config.dev.companion) {
                hotReloadRebuild(gradle, servers[activeSlot]!!, config, metadata, projectDir)
            } else {
                activeSlot = blueGreenRebuild(
                    gradle, servers, activeSlot, velocityManager, config, metadata, paperJar, projectDir
                )
            }
        }

        watcher.start()

        // Block until interrupted
        try {
            while (true) {
                Thread.sleep(1000)
                if (!shuttingDown.get() && !velocityManager.isRunning()) {
                    TerminalUI.error("Proxy process exited unexpectedly")
                    break
                }
            }
        } catch (_: InterruptedException) {
            // Ctrl+C
        } finally {
            watcher.stop()
            servers.values.forEach { it.stop() }
            velocityManager.stop()
            gradle.close()
            TerminalUI.blank()
            TerminalUI.status("Goodbye!")
        }
    }

    private fun blueGreenRebuild(
        gradle: GradleBridge,
        servers: Map<Slot, PaperServerManager>,
        activeSlot: Slot,
        velocityManager: VelocityManager,
        config: PaperPlaneConfig,
        metadata: dev.paperplane.cli.gradle.ProjectMetadata,
        paperJar: File,
        projectDir: File
    ): Slot {
        val totalStart = System.currentTimeMillis()
        val active = servers[activeSlot]!!
        val standbySlot = activeSlot.other()
        val standby = servers[standbySlot]!!
        val builtJar = File(projectDir, metadata.jarPath)

        // 1. Save world first — must complete before sync can start
        active.writeOverlayStatus("saving")
        TerminalUI.spin("Saving world...") {
            active.waitForSave()
        }

        // 2. Stop pre-warmed standby (if running) so we can sync to its directory
        if (standby.isRunning()) {
            standby.stop()
        }

        // 3. Build + sync in parallel (sync uses saved state, build produces new jar)
        active.writeOverlayStatus("building")
        val buildStart = System.currentTimeMillis()

        val syncThread = Thread({
            ServerSync.syncServerState(active.serverDir, standby.serverDir, standbySlot.port, builtJar.name)
        }, "sync-to-${standbySlot.serverName}")
        syncThread.start()

        val buildSuccess = gradle.build()
        val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

        // Wait for sync to finish (usually completes before build, especially with incremental sync)
        syncThread.join()

        if (!buildSuccess) {
            TerminalUI.error("Build failed", buildDuration)
            active.writeOverlayStatus("error", mapOf("message" to "Build failed"))
            TerminalUI.blank()
            TerminalUI.status("Waiting for changes...")
            return activeSlot
        }
        TerminalUI.success("Build succeeded", buildDuration)

        // 4. Deploy new plugin + companion to standby (sync already done)
        standby.copyPlugin(builtJar)
        if (config.dev.companion) standby.copyCompanion()

        // 5. Start standby
        val serverStart = System.currentTimeMillis()
        standby.start(paperJar, config.server.jvmArgs)
        val ready = TerminalUI.spin("Starting ${standbySlot.serverName} server...") {
            standby.waitForReady()
        }
        val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)

        if (!ready) {
            TerminalUI.error("Standby server failed to start", serverDuration)
            standby.stop()
            active.writeOverlayStatus("error", mapOf("message" to "Standby failed to start"))
            return activeSlot
        }

        // 6. Transfer players via Velocity
        velocityManager.clearTransferComplete()
        velocityManager.writeActiveServer(standbySlot.serverName, transfer = true)
        velocityManager.waitForTransferComplete()
        Thread.sleep(200) // Brief safety margin for connection establishment

        // 7. Write "ready" to new server's companion before stopping old
        val totalDuration = formatDuration(System.currentTimeMillis() - totalStart)
        standby.writeOverlayStatus("ready", mapOf("duration" to totalDuration))

        // 8. Stop old server + pre-warm it as next standby (async)
        Thread({
            active.stop()
            // Pre-warm: sync current state and start so it's ready for next rebuild
            preWarmStandby(active, standby, activeSlot.port, builtJar, config, paperJar, velocityManager)
        }, "stop-and-prewarm-${activeSlot.serverName}").apply { isDaemon = true }.start()

        // 9. Report success
        TerminalUI.success("Server ready (${standbySlot.serverName})", serverDuration)
        TerminalUI.totalTime(totalDuration)

        TerminalUI.blank()
        TerminalUI.status("Watching for changes...")

        return standbySlot
    }

    /**
     * Pre-warms a standby server by syncing state and starting it in the background.
     * This way it's already booted when the next rebuild needs it, saving cold-start time.
     */
    private fun preWarmStandby(
        standby: PaperServerManager,
        source: PaperServerManager,
        standbyPort: Int,
        pluginJar: File,
        config: PaperPlaneConfig,
        paperJar: File,
        velocityManager: VelocityManager
    ) {
        try {
            if (standby.isRunning()) return
            standby.serverDir.mkdirs()
            standby.cleanupStale()
            standby.configure()
            standby.configureVelocityForwarding(velocityManager.forwardingSecret)
            ServerSync.syncServerState(source.serverDir, standby.serverDir, standbyPort, pluginJar.name)
            standby.copyPlugin(pluginJar)
            if (config.dev.companion) standby.copyCompanion()
            standby.start(paperJar, config.server.jvmArgs)
            standby.waitForReady()
        } catch (_: Exception) {
            // Pre-warm is best-effort; failure here doesn't affect the active server
        }
    }

    // ── Hot-reload mode ──────────────────────────────────────────────────

    private fun hotReloadRebuild(
        gradle: GradleBridge,
        server: PaperServerManager,
        config: PaperPlaneConfig,
        metadata: dev.paperplane.cli.gradle.ProjectMetadata,
        projectDir: File
    ) {
        val totalStart = System.currentTimeMillis()

        // 1. Build — no world save needed, server stays running
        server.writeOverlayStatus("building")
        val buildStart = System.currentTimeMillis()
        val buildSuccess = gradle.build()
        val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

        if (!buildSuccess) {
            TerminalUI.error("Build failed", buildDuration)
            server.writeOverlayStatus("error", mapOf("message" to "Build failed"))
            TerminalUI.blank()
            TerminalUI.status("Watching for changes...")
            return
        }
        TerminalUI.success("Build succeeded", buildDuration)

        // 2. Stage new jar (don't overwrite current — allows companion to roll back)
        val builtJar = File(projectDir, metadata.jarPath)
        val stagedName = server.stagePlugin(builtJar)

        // 3. Clear flag files and signal companion to reload
        val ppDir = File(server.serverDir, ".paperplane")
        ppDir.mkdirs()
        File(ppDir, "reload-complete").delete()
        File(ppDir, "reload-failed").delete()

        server.writeOverlayStatus("reloading", mapOf(
            "pluginName" to metadata.pluginName,
            "jarFileName" to builtJar.name,
            "pendingJar" to stagedName
        ))

        // 4. Wait for confirmation from companion plugin
        val reloadStart = System.currentTimeMillis()
        val success = TerminalUI.spin("Reloading ${metadata.pluginName}...") {
            waitForReloadResult(ppDir, timeoutMs = 10_000)
        }
        val reloadDuration = formatDuration(System.currentTimeMillis() - reloadStart)

        if (success) {
            val totalDuration = formatDuration(System.currentTimeMillis() - totalStart)
            TerminalUI.success("Plugin reloaded", reloadDuration)
            TerminalUI.totalTime(totalDuration)
            server.writeOverlayStatus("ready", mapOf("duration" to totalDuration))
        } else {
            TerminalUI.error("Hot-reload failed (server still running with old plugin)", reloadDuration)
            server.writeOverlayStatus("error", mapOf("message" to "Hot-reload failed"))
        }

        TerminalUI.blank()
        TerminalUI.status("Watching for changes...")
    }

    private fun waitForReloadResult(ppDir: File, timeoutMs: Long): Boolean {
        val completeFlag = File(ppDir, "reload-complete")
        val failedFlag = File(ppDir, "reload-failed")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (completeFlag.exists()) {
                completeFlag.delete()
                return true
            }
            if (failedFlag.exists()) {
                val reason = failedFlag.readText()
                failedFlag.delete()
                TerminalUI.error("Reload failed: $reason")
                return false
            }
            Thread.sleep(100)
        }
        return false
    }

    // ── Single-server mode (proxy=false) ────────────────────────────────

    private fun runSingleServer(
        config: PaperPlaneConfig,
        ppDir: File,
        gradle: GradleBridge,
        downloader: PaperDownloader
    ) {
        val serverManager = PaperServerManager(File(ppDir, "server"), downloader, config.dev.verboseServer)

        Runtime.getRuntime().addShutdownHook(Thread {
            println() // Clear ^C line
            serverManager.stop()
            gradle.close()
        })

        val metadata = TerminalUI.spin("Reading project metadata...") {
            gradle.metadata()
        }
        if (metadata == null) {
            TerminalUI.error("Failed to read project metadata. Is the PaperPlane Gradle plugin applied?")
            return
        }

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
            waitForFixSingleServer(gradle, serverManager, config, metadata.jarPath, ppDir)
            return
        }
        TerminalUI.success("Build succeeded", buildDuration)

        val mcVersion = config.server.version ?: metadata.paperApiVersion
        val paperJar = TerminalUI.spin("Downloading Paper $mcVersion...") {
            downloader.download(mcVersion)
        }

        serverManager.cleanupStale()
        serverManager.configure()
        val builtJar = File(projectDir, metadata.jarPath)
        serverManager.copyPlugin(builtJar)
        if (config.dev.companion) serverManager.copyCompanion()

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
        if (config.dev.companion) TerminalUI.info("Companion:", "enabled")
        if (config.dev.hotReload) TerminalUI.info("Mode:", "hot-reload")
        TerminalUI.blank()
        TerminalUI.status("Watching for changes...")

        val srcDir = File(projectDir, "src")
        val watcher = FileWatcher(srcDir, config.dev.debounceMs) { changedFiles ->
            val shortName = changedFiles.firstOrNull()?.substringAfterLast("/") ?: "files"
            val extra = if (changedFiles.size > 1) " (+${changedFiles.size - 1} more)" else ""
            TerminalUI.change("Change detected: $shortName$extra")

            if (config.dev.hotReload && config.dev.companion) {
                hotReloadRebuild(gradle, serverManager, config, metadata, projectDir)
            } else {
                singleServerRebuild(gradle, serverManager, config, metadata, paperJar, projectDir)
            }
        }

        watcher.start()

        try {
            while (true) {
                Thread.sleep(1000)
                if (!serverManager.isRunning()) {
                    TerminalUI.error("Server process exited unexpectedly")
                    break
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            watcher.stop()
            serverManager.stop()
            gradle.close()
            TerminalUI.blank()
            TerminalUI.status("Goodbye!")
        }
    }

    private fun singleServerRebuild(
        gradle: GradleBridge,
        serverManager: PaperServerManager,
        config: PaperPlaneConfig,
        metadata: dev.paperplane.cli.gradle.ProjectMetadata,
        paperJar: File,
        projectDir: File
    ) {
        val totalStart = System.currentTimeMillis()
        serverManager.stop()
        serverManager.writeOverlayStatus("building")

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

        val builtJar = File(projectDir, metadata.jarPath)
        serverManager.copyPlugin(builtJar)

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

    // ── Wait-for-fix flows ──────────────────────────────────────────────

    private fun waitForFixAndRestart(
        gradle: GradleBridge,
        servers: Map<Slot, PaperServerManager>,
        velocityManager: VelocityManager,
        velocityDownloader: VelocityDownloader,
        config: PaperPlaneConfig,
        ppDir: File
    ) {
        val srcDir = File(projectDir, "src")
        val watcher = FileWatcher(srcDir, config.dev.debounceMs) { changedFiles ->
            val shortName = changedFiles.firstOrNull()?.substringAfterLast("/") ?: "files"
            TerminalUI.change("Change detected: $shortName")

            val buildStart = System.currentTimeMillis()
            val buildSuccess = gradle.build()
            val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

            if (buildSuccess) {
                TerminalUI.success("Build succeeded", buildDuration)
                val metadata = gradle.metadata() ?: return@FileWatcher
                val mcVersion = config.server.version ?: metadata.paperApiVersion
                val downloader = PaperDownloader(File(ppDir, "cache"))
                val paperJar = downloader.download(mcVersion)

                // Start proxy if not already running
                if (!velocityManager.isRunning()) {
                    val velocityJar = velocityDownloader.download()
                    velocityManager.configure(bluePort = Slot.BLUE.port, greenPort = Slot.GREEN.port)
                    velocityManager.start(velocityJar)
                    velocityManager.waitForReady(25565)
                }

                // Start blue server
                val blue = servers[Slot.BLUE]!!
                blue.cleanupStale()
                blue.configure()
                blue.configureVelocityForwarding(velocityManager.forwardingSecret)
                val builtJar = File(projectDir, metadata.jarPath)
                blue.copyPlugin(builtJar)
                if (config.dev.companion) blue.copyCompanion()

                val serverStart = System.currentTimeMillis()
                blue.start(paperJar, config.server.jvmArgs)
                val ready = blue.waitForReady()
                val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)
                if (ready) {
                    TerminalUI.success("Server ready", serverDuration)
                    blue.writeOverlayStatus("ready", mapOf("duration" to serverDuration))
                    velocityManager.writeActiveServer("blue")
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
            servers.values.forEach { it.stop() }
            velocityManager.stop()
            gradle.close()
        }
    }

    private fun waitForFixSingleServer(
        gradle: GradleBridge,
        serverManager: PaperServerManager,
        config: PaperPlaneConfig,
        jarPath: String,
        ppDir: File
    ) {
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
                val metadata = gradle.metadata() ?: return@FileWatcher
                val mcVersion = config.server.version ?: metadata.paperApiVersion
                val downloader = PaperDownloader(File(ppDir, "cache"))
                val paperJar = downloader.download(mcVersion)
                serverManager.cleanupStale()
                serverManager.configure()
                val builtJar = File(projectDir, metadata.jarPath)
                serverManager.copyPlugin(builtJar)
                if (config.dev.companion) serverManager.copyCompanion()

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

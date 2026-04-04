package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.config.DevMode
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.gradle.BuildSnapshot
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.server.JbrDownloader
import dev.paperplane.cli.server.PaperDownloader
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.server.ServerSync
import dev.paperplane.cli.server.VelocityDownloader
import dev.paperplane.cli.server.VelocityManager
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.watcher.FileWatcher
import java.io.File

class DevCommand : CliktCommand(name = "dev") {
    private val modeFlag by option("--mode", "-m", help = "Dev mode: hot-reload, blue-green, restart")
    private val projectDir = File(System.getProperty("user.dir"))

    private enum class Slot(val serverName: String, val port: Int) {
        SERVER("server", 25566),
        SWAP("swap", 25567);
        fun other() = if (this == SERVER) SWAP else SERVER
    }

    override fun run() {
        val version = javaClass.`package`?.implementationVersion ?: "0.1.0"
        TerminalUI.header(version)

        val config = PaperPlaneConfig.load(projectDir)
        val ppDir = File(projectDir, ".paperplane")
        ppDir.mkdirs()

        // Migrate old blue/green directory layout
        migrateOldLayout(ppDir)

        val gradle = GradleBridge(projectDir)
        val downloader = PaperDownloader(File(ppDir, "cache"))

        // CLI --mode flag overrides config
        val resolvedMode = modeFlag?.let {
            try { DevMode.valueOf(it.uppercase().replace("-", "_")) }
            catch (_: Exception) {
                TerminalUI.error("Unknown mode: $it (expected: hot-reload, blue-green, restart)")
                return
            }
        } ?: config.dev.mode

        when (resolvedMode) {
            DevMode.HOT_RELOAD -> runHotReload(config, ppDir, gradle, downloader)
            DevMode.BLUE_GREEN -> runBlueGreen(config, ppDir, gradle, downloader)
            DevMode.RESTART -> runRestart(config, ppDir, gradle, downloader)
        }
    }

    private fun migrateOldLayout(ppDir: File) {
        val oldBlue = File(ppDir, "server-blue")
        val newServer = File(ppDir, "server")
        if (oldBlue.exists() && !newServer.exists()) {
            oldBlue.renameTo(newServer)
            TerminalUI.info("Migrated:", "server-blue/ → server/")
        }
        val oldGreen = File(ppDir, "server-green")
        if (oldGreen.exists()) {
            oldGreen.deleteRecursively()
            TerminalUI.info("Cleaned up:", "server-green/ (no longer needed)")
        }
    }

    // ── Blue/Green mode ─────────────────────────────────────────────────

    private fun runBlueGreen(
        config: PaperPlaneConfig,
        ppDir: File,
        gradle: GradleBridge,
        downloader: PaperDownloader
    ) {
        val servers = mapOf(
            Slot.SERVER to PaperServerManager(File(ppDir, "server"), downloader, Slot.SERVER.port),
            Slot.SWAP to PaperServerManager(File(ppDir, "server-swap"), downloader, Slot.SWAP.port)
        )
        var activeSlot = Slot.SERVER

        val velocityDownloader = VelocityDownloader(File(ppDir, "cache"))
        val velocityManager = VelocityManager(File(ppDir, "proxy"))

        // Shutdown hook
        val shuttingDown = java.util.concurrent.atomic.AtomicBoolean(false)
        Runtime.getRuntime().addShutdownHook(Thread {
            shuttingDown.set(true)
            TerminalUI.discardBlock()
            println()
            servers.values.forEach { it.stop() }
            velocityManager.stop()
            gradle.close()
        })

        // Step 1: Get metadata
        TerminalUI.beginBlock()
        val metadata = TerminalUI.spin("Reading project metadata...") {
            gradle.metadata()
        }
        if (metadata == null) {
            TerminalUI.error("Failed to read project metadata. Is the PaperPlane Gradle plugin applied?")
            TerminalUI.endBlock()
            return
        }

        // Step 2: Build
        val buildStart = System.currentTimeMillis()
        val active = servers[activeSlot]!!
        active.writeCompanionStatus("building")
        val buildSuccess = TerminalUI.spin("Building...") {
            gradle.build()
        }
        val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

        if (!buildSuccess) {
            TerminalUI.error("Build failed", buildDuration)
            active.writeCompanionStatus("error", mapOf("message" to "Build failed"))
            TerminalUI.awaitChanges(watching = false)
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
        velocityManager.configure(serverPort = Slot.SERVER.port, swapPort = Slot.SWAP.port)

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
        active.copyCompanion()

        val serverStart = System.currentTimeMillis()
        active.start(paperJar, config.server.jvmArgs)
        val ready = TerminalUI.spin("Starting Paper $mcVersion server...") {
            active.waitForReady()
        }
        val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)

        if (ready) {
            TerminalUI.success("Paper $mcVersion server ready", serverDuration)
            active.writeCompanionStatus("ready", mapOf("duration" to serverDuration))
        } else {
            TerminalUI.error("Server failed to start", serverDuration)
            active.writeCompanionStatus("error", mapOf("message" to "Server failed to start"))
            TerminalUI.endBlock()
            return
        }
        TerminalUI.endBlock()

        TerminalUI.beginBlock()
        TerminalUI.info("Server:", "localhost:25565 (via proxy)")
        TerminalUI.info("Plugin:", "${metadata.pluginName} v${metadata.version}")
        TerminalUI.info("Mode:", "blue-green (zero-downtime)")
        TerminalUI.awaitChanges()

        // Step 6: Pre-warm standby server in background
        preWarmStandby(servers[Slot.SWAP]!!, active, Slot.SWAP.port, builtJar, config, paperJar, velocityManager)

        // Step 7: Watch and rebuild loop
        val srcDir = File(projectDir, "src")
        val watcher = FileWatcher(srcDir, config.dev.debounceMs) { changedFiles ->
            TerminalUI.discardBlock()
            TerminalUI.beginBlock()
            val shortName = changedFiles.firstOrNull()?.substringAfterLast("/") ?: "files"
            val extra = if (changedFiles.size > 1) " (+${changedFiles.size - 1} more)" else ""
            TerminalUI.change("Change detected: $shortName$extra")

            activeSlot = blueGreenRebuild(
                gradle, servers, activeSlot, velocityManager, config, metadata, paperJar, projectDir
            )
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
            TerminalUI.discardBlock()
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
        active.writeCompanionStatus("saving")
        TerminalUI.spin("Saving world...") {
            active.waitForSave()
        }

        // 2. Stop pre-warmed standby (if running) so we can sync to its directory
        if (standby.isRunning()) {
            standby.stop()
        }

        // 3. Build + sync in parallel (sync uses saved state, build produces new jar)
        active.writeCompanionStatus("building")
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
            active.writeCompanionStatus("error", mapOf("message" to "Build failed"))
            TerminalUI.awaitChanges(watching = false)
            return activeSlot
        }
        TerminalUI.success("Build succeeded", buildDuration)

        // 4. Deploy new plugin + companion to standby (sync already done)
        standby.copyPlugin(builtJar)
        standby.copyCompanion()

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
            active.writeCompanionStatus("error", mapOf("message" to "Standby failed to start"))
            TerminalUI.awaitChanges(watching = false)
            return activeSlot
        }

        // 6. Transfer players via Velocity
        velocityManager.clearTransferComplete()
        velocityManager.writeActiveServer(standbySlot.serverName, transfer = true)
        velocityManager.waitForTransferComplete()
        Thread.sleep(200) // Brief safety margin for connection establishment

        // 7. Write "ready" to new server's companion before stopping old
        val totalDuration = formatDuration(System.currentTimeMillis() - totalStart)
        standby.writeCompanionStatus("ready", mapOf("duration" to totalDuration))

        // 8. Stop old server + pre-warm it as next standby (async)
        Thread({
            active.stop()
            // Pre-warm: sync current state and start so it's ready for next rebuild
            preWarmStandby(active, standby, activeSlot.port, builtJar, config, paperJar, velocityManager)
        }, "stop-and-prewarm-${activeSlot.serverName}").apply { isDaemon = true }.start()

        // 9. Report success
        TerminalUI.success("Server ready (${standbySlot.serverName})", serverDuration)
        TerminalUI.totalTime(totalDuration)
        TerminalUI.awaitChanges()

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
            standby.copyCompanion()
            standby.start(paperJar, config.server.jvmArgs)
            standby.waitForReady()
        } catch (_: Exception) {
            // Pre-warm is best-effort; failure here doesn't affect the active server
        }
    }

    // ── Hot-reload mode ──────────────────────────────────────────────────

    private var buildSnapshot: BuildSnapshot? = null
    private var cachedFastMeta: dev.paperplane.cli.gradle.ProjectMetadata? = null
    private var lastPostBuildSnapshot: Map<String, Long>? = null

    private fun hotReloadRebuild(
        gradle: GradleBridge,
        server: PaperServerManager,
        config: PaperPlaneConfig,
        metadata: dev.paperplane.cli.gradle.ProjectMetadata,
        projectDir: File
    ) {
        val totalStart = System.currentTimeMillis()

        // 1. Resolve metadata (cached — classesDir/resourcesDir don't change between rebuilds)
        if (cachedFastMeta == null) cachedFastMeta = gradle.metadataFast()
        val fastMeta = cachedFastMeta

        // 2. Snapshot .class files BEFORE build (for change detection)
        val classesDir = if (fastMeta != null && fastMeta.classesDir.isNotEmpty()) {
            File(fastMeta.classesDir)
        } else {
            File(projectDir, "build/classes/kotlin/main")
        }
        if (buildSnapshot == null) buildSnapshot = BuildSnapshot(classesDir)
        val preBuildSnapshot = lastPostBuildSnapshot ?: buildSnapshot!!.take()

        // 3. Compile only — skip JAR packaging for faster rebuilds
        server.writeCompanionStatus("building")
        val buildStart = System.currentTimeMillis()
        val buildSuccess = gradle.compileOnly()
        val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

        if (!buildSuccess) {
            TerminalUI.error("Build failed", buildDuration)
            server.writeCompanionStatus("error", mapOf("message" to "Build failed"))
            TerminalUI.awaitChanges(watching = false)
            return
        }
        TerminalUI.success("Build succeeded", buildDuration)

        // 4. Diff .class files to determine change type
        val postBuildSnapshot = buildSnapshot!!.take()
        lastPostBuildSnapshot = postBuildSnapshot
        val changes = BuildSnapshot.diff(preBuildSnapshot, postBuildSnapshot)

        // 5. Clear flag files and signal companion to reload
        val ppDir = File(server.serverDir, ".paperplane")
        ppDir.mkdirs()
        File(ppDir, "reload-complete").delete()
        File(ppDir, "reload-failed").delete()

        if (fastMeta != null && fastMeta.classesDir.isNotEmpty()) {
            val allDirs: List<String> = listOf(fastMeta.classesDir, fastMeta.resourcesDir) + fastMeta.runtimeClasspath

            // Choose strategy based on change type
            val strategy = if (changes.noNewOrRemovedClasses && changes.modified.isNotEmpty()) "hotswap" else "directory"
            TerminalUI.info("Strategy:", if (strategy == "hotswap") "hotswap (${changes.modified.size} modified)" else "directory reload")

            val statusExtra = mutableMapOf<String, Any>(
                "pluginName" to metadata.pluginName,
                "jarFileName" to File(metadata.jarPath).name,
                "reloadStrategy" to strategy,
                "buildOutputDirs" to allDirs
            )
            if (strategy == "hotswap") {
                statusExtra["changedClasses"] = changes.modified
            }
            server.writeCompanionStatus("reloading", statusExtra)
        } else {
            // Fallback: stage JAR and use traditional reload
            TerminalUI.info("Strategy:", "jar (fallback)")
            val builtJar = File(projectDir, metadata.jarPath)
            if (!builtJar.exists()) gradle.build()
            val stagedName = server.stagePlugin(builtJar)
            server.writeCompanionStatus("reloading", mapOf(
                "pluginName" to metadata.pluginName,
                "jarFileName" to builtJar.name,
                "pendingJar" to stagedName
            ))
        }

        // 6. Wait for confirmation from companion plugin
        val reloadStart = System.currentTimeMillis()
        val success = TerminalUI.spin("Reloading ${metadata.pluginName}...") {
            waitForReloadResult(ppDir, timeoutMs = 10_000)
        }
        val reloadDuration = formatDuration(System.currentTimeMillis() - reloadStart)

        if (success) {
            val totalDuration = formatDuration(System.currentTimeMillis() - totalStart)
            TerminalUI.success("Plugin reloaded", reloadDuration)
            TerminalUI.totalTime(totalDuration)
            server.writeCompanionStatus("ready", mapOf("duration" to totalDuration))
        } else {
            TerminalUI.error("Hot-reload failed (server still running with old plugin)", reloadDuration)
            server.writeCompanionStatus("error", mapOf("message" to "Hot-reload failed"))
        }

        TerminalUI.awaitChanges()
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

    // ── Hot-reload mode ───────────────────────────────────────────────

    private fun runHotReload(
        config: PaperPlaneConfig,
        ppDir: File,
        gradle: GradleBridge,
        downloader: PaperDownloader
    ) {
        val serverManager = PaperServerManager(File(ppDir, "server"), downloader)

        Runtime.getRuntime().addShutdownHook(Thread {
            TerminalUI.discardBlock()
            println()
            serverManager.stop()
            gradle.close()
        })

        TerminalUI.beginBlock()
        val metadata = TerminalUI.spin("Reading project metadata...") { gradle.metadata() }
        if (metadata == null) {
            TerminalUI.error("Failed to read project metadata. Is the PaperPlane Gradle plugin applied?")
            TerminalUI.endBlock()
            return
        }

        val buildStart = System.currentTimeMillis()
        serverManager.writeCompanionStatus("building")
        val buildSuccess = TerminalUI.spin("Building...") { gradle.build() }
        val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

        if (!buildSuccess) {
            TerminalUI.error("Build failed", buildDuration)
            serverManager.writeCompanionStatus("error", mapOf("message" to "Build failed"))
            TerminalUI.awaitChanges(watching = false)
            waitForFixSingleServer(gradle, serverManager, config, downloader)
            return
        }
        TerminalUI.success("Build succeeded", buildDuration)

        val mcVersion = config.server.version ?: metadata.paperApiVersion
        val paperJar = TerminalUI.spin("Downloading Paper $mcVersion...") { downloader.download(mcVersion) }

        serverManager.cleanupStale()
        serverManager.configure()
        val builtJar = File(projectDir, metadata.jarPath)
        serverManager.copyPlugin(builtJar)
        serverManager.copyCompanion()

        // Resolve JBR for Level 3 HMR
        val java = resolveJava(config, ppDir)
        val jvmArgs = if (java.isJbr) {
            config.server.jvmArgs + "-XX:+AllowEnhancedClassRedefinition"
        } else {
            config.server.jvmArgs
        }

        val serverStart = System.currentTimeMillis()
        serverManager.start(paperJar, jvmArgs, hotReload = true, javaBin = java.bin)
        val ready = TerminalUI.spin("Starting Paper $mcVersion server...") { serverManager.waitForReady() }
        val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)

        if (ready) {
            TerminalUI.success("Paper $mcVersion server ready", serverDuration)
            serverManager.writeCompanionStatus("ready", mapOf("duration" to serverDuration))
        } else {
            TerminalUI.error("Server failed to start", serverDuration)
            serverManager.writeCompanionStatus("error", mapOf("message" to "Server failed to start"))
            TerminalUI.endBlock()
            return
        }
        TerminalUI.endBlock()

        TerminalUI.beginBlock()
        TerminalUI.info("Server:", "localhost:25565")
        TerminalUI.info("Plugin:", "${metadata.pluginName} v${metadata.version}")
        TerminalUI.info("Mode:", if (java.isJbr) "hot-reload (enhanced — JBR)" else "hot-reload")
        TerminalUI.awaitChanges()

        val srcDir = File(projectDir, "src")
        val watcher = FileWatcher(srcDir, config.dev.debounceMs) { changedFiles ->
            TerminalUI.discardBlock()
            TerminalUI.beginBlock()
            val shortName = changedFiles.firstOrNull()?.substringAfterLast("/") ?: "files"
            val extra = if (changedFiles.size > 1) " (+${changedFiles.size - 1} more)" else ""
            TerminalUI.change("Change detected: $shortName$extra")
            hotReloadRebuild(gradle, serverManager, config, metadata, projectDir)
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
            TerminalUI.discardBlock()
        }
    }

    // ── Restart mode (simple stop/build/start) ────────────────────────

    private fun runRestart(
        config: PaperPlaneConfig,
        ppDir: File,
        gradle: GradleBridge,
        downloader: PaperDownloader
    ) {
        val serverManager = PaperServerManager(File(ppDir, "server"), downloader)

        Runtime.getRuntime().addShutdownHook(Thread {
            TerminalUI.discardBlock()
            println()
            serverManager.stop()
            gradle.close()
        })

        TerminalUI.beginBlock()
        val metadata = TerminalUI.spin("Reading project metadata...") { gradle.metadata() }
        if (metadata == null) {
            TerminalUI.error("Failed to read project metadata. Is the PaperPlane Gradle plugin applied?")
            TerminalUI.endBlock()
            return
        }

        val buildStart = System.currentTimeMillis()
        serverManager.writeCompanionStatus("building")
        val buildSuccess = TerminalUI.spin("Building...") { gradle.build() }
        val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

        if (!buildSuccess) {
            TerminalUI.error("Build failed", buildDuration)
            serverManager.writeCompanionStatus("error", mapOf("message" to "Build failed"))
            TerminalUI.awaitChanges(watching = false)
            waitForFixSingleServer(gradle, serverManager, config, downloader)
            return
        }
        TerminalUI.success("Build succeeded", buildDuration)

        val mcVersion = config.server.version ?: metadata.paperApiVersion
        val paperJar = TerminalUI.spin("Downloading Paper $mcVersion...") { downloader.download(mcVersion) }

        serverManager.cleanupStale()
        serverManager.configure()
        val builtJar = File(projectDir, metadata.jarPath)
        serverManager.copyPlugin(builtJar)
        serverManager.copyCompanion()

        val serverStart = System.currentTimeMillis()
        serverManager.start(paperJar, config.server.jvmArgs)
        val ready = TerminalUI.spin("Starting Paper $mcVersion server...") { serverManager.waitForReady() }
        val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)

        if (ready) {
            TerminalUI.success("Paper $mcVersion server ready", serverDuration)
            serverManager.writeCompanionStatus("ready", mapOf("duration" to serverDuration))
        } else {
            TerminalUI.error("Server failed to start", serverDuration)
            serverManager.writeCompanionStatus("error", mapOf("message" to "Server failed to start"))
            TerminalUI.endBlock()
            return
        }
        TerminalUI.endBlock()

        TerminalUI.beginBlock()
        TerminalUI.info("Server:", "localhost:25565")
        TerminalUI.info("Plugin:", "${metadata.pluginName} v${metadata.version}")
        TerminalUI.info("Mode:", "restart")
        TerminalUI.awaitChanges()

        val srcDir = File(projectDir, "src")
        val watcher = FileWatcher(srcDir, config.dev.debounceMs) { changedFiles ->
            TerminalUI.discardBlock()
            TerminalUI.beginBlock()
            val shortName = changedFiles.firstOrNull()?.substringAfterLast("/") ?: "files"
            val extra = if (changedFiles.size > 1) " (+${changedFiles.size - 1} more)" else ""
            TerminalUI.change("Change detected: $shortName$extra")
            restartRebuild(gradle, serverManager, config, metadata, paperJar, projectDir)
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
            TerminalUI.discardBlock()
        }
    }

    private fun restartRebuild(
        gradle: GradleBridge,
        serverManager: PaperServerManager,
        config: PaperPlaneConfig,
        metadata: dev.paperplane.cli.gradle.ProjectMetadata,
        paperJar: File,
        projectDir: File
    ) {
        val totalStart = System.currentTimeMillis()
        serverManager.stop()

        val buildStart = System.currentTimeMillis()
        val buildSuccess = gradle.build()
        val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

        if (!buildSuccess) {
            TerminalUI.error("Build failed", buildDuration)
            TerminalUI.awaitChanges(watching = false)
            return
        }
        TerminalUI.success("Build succeeded", buildDuration)

        val builtJar = File(projectDir, metadata.jarPath)
        serverManager.copyPlugin(builtJar)
        serverManager.copyCompanion()

        val serverStart = System.currentTimeMillis()
        serverManager.start(paperJar, config.server.jvmArgs)
        val ready = serverManager.waitForReady()
        val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)

        if (ready) {
            TerminalUI.success("Server ready", serverDuration)
            val totalDuration = formatDuration(System.currentTimeMillis() - totalStart)
            TerminalUI.totalTime(totalDuration)
            serverManager.writeCompanionStatus("ready", mapOf("duration" to totalDuration))
        } else {
            TerminalUI.error("Server failed to start", serverDuration)
        }

        TerminalUI.awaitChanges()
    }

    // ── JBR resolution ──────────────────────────────────────────────────

    private data class JavaRuntime(val bin: String, val isJbr: Boolean)

    private fun resolveJava(config: PaperPlaneConfig, ppDir: File): JavaRuntime {
        return when (config.dev.jbr) {
            "auto" -> {
                if (checkIsJbr("java")) JavaRuntime("java", true)
                else {
                    val jbrDownloader = JbrDownloader(File(ppDir, "cache"))
                    val javaBin = TerminalUI.spin("Downloading JBR...") { jbrDownloader.download() }
                    JavaRuntime(javaBin.absolutePath, true)
                }
            }
            "on" -> {
                val jbrDownloader = JbrDownloader(File(ppDir, "cache"))
                val javaBin = TerminalUI.spin("Downloading JBR...") { jbrDownloader.download() }
                JavaRuntime(javaBin.absolutePath, true)
            }
            "off" -> JavaRuntime("java", false)
            else -> JavaRuntime(config.dev.jbr, checkIsJbr(config.dev.jbr))
        }
    }

    private fun checkIsJbr(javaBin: String): Boolean {
        return try {
            val proc = ProcessBuilder(javaBin, "-version")
                .redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            output.contains("JetBrains", ignoreCase = true) || output.contains("JBR", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    // ── Wait-for-fix flows ────────────────────────────────────────────

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
            TerminalUI.discardBlock()
            TerminalUI.beginBlock()
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
                    velocityManager.configure(serverPort = Slot.SERVER.port, swapPort = Slot.SWAP.port)
                    velocityManager.start(velocityJar)
                    velocityManager.waitForReady(25565)
                }

                // Start blue server
                val blue = servers[Slot.SERVER]!!
                blue.cleanupStale()
                blue.configure()
                blue.configureVelocityForwarding(velocityManager.forwardingSecret)
                val builtJar = File(projectDir, metadata.jarPath)
                blue.copyPlugin(builtJar)
                blue.copyCompanion()

                val serverStart = System.currentTimeMillis()
                blue.start(paperJar, config.server.jvmArgs)
                val ready = blue.waitForReady()
                val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)
                if (ready) {
                    TerminalUI.success("Server ready", serverDuration)
                    blue.writeCompanionStatus("ready", mapOf("duration" to serverDuration))
                    velocityManager.writeActiveServer("server")
                }
                TerminalUI.awaitChanges()
            } else {
                TerminalUI.error("Build failed", buildDuration)
                TerminalUI.awaitChanges(watching = false)
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
        downloader: PaperDownloader
    ) {
        val srcDir = File(projectDir, "src")
        val watcher = FileWatcher(srcDir, config.dev.debounceMs) { changedFiles ->
            TerminalUI.discardBlock()
            TerminalUI.beginBlock()
            val shortName = changedFiles.firstOrNull()?.substringAfterLast("/") ?: "files"
            TerminalUI.change("Change detected: $shortName")

            val buildStart = System.currentTimeMillis()
            serverManager.writeCompanionStatus("building")
            val buildSuccess = gradle.build()
            val buildDuration = formatDuration(System.currentTimeMillis() - buildStart)

            if (buildSuccess) {
                TerminalUI.success("Build succeeded", buildDuration)
                val metadata = gradle.metadata() ?: return@FileWatcher
                val mcVersion = config.server.version ?: metadata.paperApiVersion
                val paperJar = downloader.download(mcVersion)
                serverManager.cleanupStale()
                serverManager.configure()
                val builtJar = File(projectDir, metadata.jarPath)
                serverManager.copyPlugin(builtJar)
                serverManager.copyCompanion()

                val serverStart = System.currentTimeMillis()
                serverManager.start(paperJar, config.server.jvmArgs)
                val ready = serverManager.waitForReady()
                val serverDuration = formatDuration(System.currentTimeMillis() - serverStart)
                if (ready) {
                    TerminalUI.success("Server ready", serverDuration)
                    serverManager.writeCompanionStatus("ready", mapOf("duration" to serverDuration))
                }
                TerminalUI.awaitChanges()
            } else {
                TerminalUI.error("Build failed", buildDuration)
                TerminalUI.awaitChanges(watching = false)
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

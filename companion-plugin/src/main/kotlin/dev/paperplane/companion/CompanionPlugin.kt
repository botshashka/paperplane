package dev.paperplane.companion

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class CompanionPlugin : JavaPlugin() {
    private lateinit var errorCatcher: ErrorCatcher
    private lateinit var buildStatusBar: BuildStatusBar

    override fun onEnable() {
        try {
            // Patch JavaPlugin constructor for dev-mode class loading.
            // Must happen before any directory-based reloads.
            JavaPluginPatcher.patchIfNeeded()

            errorCatcher = ErrorCatcher(this)
            buildStatusBar = BuildStatusBar(this)

            server.pluginManager.registerEvents(errorCatcher, this)
            server.pluginManager.registerEvents(AutoOpListener(), this)
            server.pluginManager.registerEvents(SaveProtectionListener(buildStatusBar), this)
            buildStatusBar.start()

            // Signal CLI that server is ready AFTER full initialization (worlds, protocol, datapacks)
            // ServerLoadEvent fires after all plugins are loaded AND the server is fully started.
            // Writing in onEnable() is too early — protocol handlers aren't ready yet.
            server.pluginManager.registerEvents(object : Listener {
                @EventHandler
                fun onServerLoad(@Suppress("UNUSED_PARAMETER") event: ServerLoadEvent) {
                    val readyFlag = File(dataFolder.parentFile.parentFile, ".paperplane/server-ready")
                    readyFlag.parentFile.mkdirs()
                    readyFlag.writeText(System.currentTimeMillis().toString())
                }
            }, this)

            logger.info("PaperPlane companion enabled")
        } catch (e: Exception) {
            logger.severe("PaperPlane companion failed to enable: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onDisable() {
        try {
            buildStatusBar.stop()
        } catch (_: Exception) {}
        logger.info("PaperPlane companion disabled")
    }
}

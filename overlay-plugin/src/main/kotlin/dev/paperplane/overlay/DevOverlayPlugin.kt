package dev.paperplane.overlay

import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class DevOverlayPlugin : JavaPlugin() {
    private lateinit var errorCatcher: ErrorCatcher
    private lateinit var buildStatusBar: BuildStatusBar

    override fun onEnable() {
        try {
            errorCatcher = ErrorCatcher(this)
            buildStatusBar = BuildStatusBar(this)

            server.pluginManager.registerEvents(errorCatcher, this)
            server.pluginManager.registerEvents(AutoOpListener(), this)
            server.pluginManager.registerEvents(SaveProtectionListener(buildStatusBar), this)
            buildStatusBar.start()

            // Signal to CLI that the server is fully ready (all plugins loaded)
            val readyFlag = File(dataFolder.parentFile.parentFile, ".paperplane/server-ready")
            readyFlag.parentFile.mkdirs()
            readyFlag.writeText(System.currentTimeMillis().toString())

            logger.info("PaperPlane dev overlay enabled")
        } catch (e: Exception) {
            logger.severe("PaperPlane overlay failed to enable: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onDisable() {
        try {
            buildStatusBar.stop()
        } catch (_: Exception) {}
        logger.info("PaperPlane dev overlay disabled")
    }
}

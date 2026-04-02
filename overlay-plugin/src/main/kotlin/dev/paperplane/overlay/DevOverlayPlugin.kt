package dev.paperplane.overlay

import org.bukkit.plugin.java.JavaPlugin

class DevOverlayPlugin : JavaPlugin() {
    private lateinit var errorCatcher: ErrorCatcher
    private lateinit var buildStatusBar: BuildStatusBar

    override fun onEnable() {
        try {
            errorCatcher = ErrorCatcher(this)
            buildStatusBar = BuildStatusBar(this)

            server.pluginManager.registerEvents(errorCatcher, this)
            server.pluginManager.registerEvents(AutoOpListener(), this)
            buildStatusBar.start()

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

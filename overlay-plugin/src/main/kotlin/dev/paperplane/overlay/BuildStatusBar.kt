package dev.paperplane.overlay

import com.google.gson.Gson
import com.google.gson.JsonObject
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File

class BuildStatusBar(private val plugin: JavaPlugin) {
    private val gson = Gson()
    private var pollTask: BukkitTask? = null
    private var lastState: String? = null
    var isSaving: Boolean = false
        private set

    private val prefix: Component = Component.text()
        .append(Component.text("PaperPlane ", NamedTextColor.AQUA))
        .build()

    fun start() {
        // Poll overlay-status.json every 5 ticks (250ms)
        pollTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { poll() }, 5L, 5L)
    }

    fun stop() {
        pollTask?.cancel()
    }

    private fun poll() {
        val statusFile = File(plugin.dataFolder.parentFile.parentFile, ".paperplane/overlay-status.json")
        if (!statusFile.exists()) return

        try {
            val json = gson.fromJson(statusFile.readText(), JsonObject::class.java)
            val state = json.get("state")?.asString ?: return

            if (state == lastState) return
            lastState = state
            isSaving = (state == "saving")

            when (state) {
                "saving" -> {
                    broadcast(Component.text("Saving world...", NamedTextColor.GOLD))
                    performSave()
                }
                "building" -> {
                    broadcast(Component.text("Rebuilding...", NamedTextColor.YELLOW))
                }
                "ready" -> {
                    val duration = json.get("duration")?.asString ?: ""
                    broadcast(Component.text("Ready $duration", NamedTextColor.GREEN))
                }
                "error" -> {
                    val message = json.get("message")?.asString ?: "Build error"
                    broadcast(Component.text(message, NamedTextColor.RED))
                }
            }
        } catch (_: Exception) {
            // Ignore parse errors from partially-written files
        }
    }

    private fun performSave() {
        try {
            plugin.server.savePlayers()
            for (world in plugin.server.worlds) {
                world.save()
            }
            val flagFile = File(plugin.dataFolder.parentFile.parentFile, ".paperplane/save-complete")
            flagFile.parentFile.mkdirs()
            flagFile.writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
            plugin.logger.warning("Failed to save: ${e.message}")
        }
    }

    private fun broadcast(message: Component) {
        val full = Component.text().append(prefix).append(message).build()
        for (player in plugin.server.onlinePlayers) {
            player.sendMessage(full)
        }
    }
}

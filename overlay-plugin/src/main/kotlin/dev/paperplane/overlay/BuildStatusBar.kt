package dev.paperplane.overlay

import com.google.gson.Gson
import com.google.gson.JsonObject
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File

class BuildStatusBar(private val plugin: JavaPlugin) {
    private val gson = Gson()
    private var bossBar: BossBar? = null
    private var pollTask: BukkitTask? = null
    private var hideTask: BukkitTask? = null
    private var lastState: String? = null

    fun start() {
        bossBar = BossBar.bossBar(
            Component.text("PaperPlane", NamedTextColor.GRAY),
            0f,
            BossBar.Color.WHITE,
            BossBar.Overlay.PROGRESS
        )

        // Poll overlay-status.json every 20 ticks (1 second)
        pollTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { poll() }, 20L, 20L)
    }

    fun stop() {
        pollTask?.cancel()
        hideTask?.cancel()
        bossBar?.let { bar ->
            for (player in plugin.server.onlinePlayers) {
                player.hideBossBar(bar)
            }
        }
    }

    private fun poll() {
        val statusFile = File(plugin.dataFolder.parentFile.parentFile, ".paperplane/overlay-status.json")
        if (!statusFile.exists()) return

        try {
            val json = gson.fromJson(statusFile.readText(), JsonObject::class.java)
            val state = json.get("state")?.asString ?: return

            if (state == lastState) return
            lastState = state

            val bar = bossBar ?: return

            when (state) {
                "building" -> {
                    hideTask?.cancel()
                    bar.name(Component.text("Rebuilding...", NamedTextColor.YELLOW))
                    bar.color(BossBar.Color.YELLOW)
                    bar.progress(1f)
                    showToAll(bar)
                }
                "ready" -> {
                    val duration = json.get("duration")?.asString ?: ""
                    bar.name(Component.text("Ready $duration", NamedTextColor.GREEN))
                    bar.color(BossBar.Color.GREEN)
                    bar.progress(1f)
                    showToAll(bar)
                    // Fade after 3 seconds
                    hideTask?.cancel()
                    hideTask = plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        hideFromAll(bar)
                    }, 60L) // 3 seconds
                }
                "error" -> {
                    hideTask?.cancel()
                    val message = json.get("message")?.asString ?: "Build error"
                    bar.name(Component.text(message, NamedTextColor.RED))
                    bar.color(BossBar.Color.RED)
                    bar.progress(1f)
                    showToAll(bar)
                }
            }
        } catch (_: Exception) {
            // Ignore parse errors from partially-written files
        }
    }

    private fun showToAll(bar: BossBar) {
        for (player in plugin.server.onlinePlayers) {
            player.showBossBar(bar)
        }
    }

    private fun hideFromAll(bar: BossBar) {
        for (player in plugin.server.onlinePlayers) {
            player.hideBossBar(bar)
        }
    }
}

package dev.paperplane.companion

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
    private var reloader: PluginReloader? = null

    private val prefix: Component = Component.text()
        .append(Component.text("PaperPlane ", NamedTextColor.AQUA))
        .build()

    fun start() {
        // Poll companion-status.json every 5 ticks (250ms)
        pollTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { poll() }, 5L, 5L)
    }

    fun stop() {
        pollTask?.cancel()
    }

    private fun poll() {
        val statusFile = File(plugin.dataFolder.parentFile.parentFile, ".paperplane/companion-status.json")
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
                "reloading" -> {
                    val pluginName = json.get("pluginName")?.asString ?: return
                    val jarFileName = json.get("jarFileName")?.asString ?: return
                    val pendingJar = json.get("pendingJar")?.asString
                    performReload(pluginName, jarFileName, pendingJar)
                    // Don't reset lastState — the CLI always writes "building" before
                    // "reloading", so the next reload will naturally pass dedup.
                    // Resetting to null causes double-processing when the next poll
                    // fires before the CLI updates status to "ready".
                }
            }
        } catch (_: Exception) {
            // Ignore parse errors from partially-written files
        }
    }

    private fun performReload(pluginName: String, jarFileName: String, pendingJarName: String?) {
        val pluginsDir = plugin.dataFolder.parentFile.absoluteFile // server's plugins/ dir
        val currentJar = File(pluginsDir, jarFileName)
        // pendingJar is an absolute path (staged in .paperplane/, not plugins/)
        val pendingJar = pendingJarName?.let { File(it) }
        val loadJar = pendingJar ?: currentJar
        val ppDir = File(plugin.dataFolder.parentFile.parentFile, ".paperplane")
        ppDir.mkdirs()

        broadcast(Component.text("Reloading $pluginName...", NamedTextColor.GOLD))

        if (reloader == null) {
            reloader = PluginReloader(plugin.server, plugin.logger)
        }

        // Pass the original jar as rollback target (only meaningful when staging)
        val rollbackJar = if (pendingJar != null) currentJar else null
        val outcome = reloader!!.reload(pluginName, loadJar, rollbackJar)
        val timingInfo = "teardown=${outcome.teardownMs},load=${outcome.loadMs},total=${outcome.totalMs}"

        when (outcome.result) {
            ReloadResult.SUCCESS -> {
                // Finalize: replace original jar with the staged .new jar
                if (pendingJar != null && pendingJar.exists()) {
                    try {
                        java.nio.file.Files.move(
                            pendingJar.toPath(), currentJar.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (e: Exception) {
                        plugin.logger.warning("Failed to finalize staged jar: ${e.message}")
                    }
                }
                File(ppDir, "reload-complete").writeText(timingInfo)
                broadcast(Component.text("$pluginName reloaded!", NamedTextColor.GREEN))
            }
            ReloadResult.ROLLBACK_SUCCESS -> {
                // New jar failed but old plugin was restored — clean up staged jar
                pendingJar?.delete()
                File(ppDir, "reload-failed").writeText("ROLLBACK_SUCCESS")
                broadcast(Component.text("Reload failed, reverted to previous version", NamedTextColor.YELLOW))
            }
            else -> {
                // Total failure — clean up staged jar
                pendingJar?.delete()
                File(ppDir, "reload-failed").writeText(outcome.result.name)
                broadcast(Component.text("Reload failed: ${outcome.result}", NamedTextColor.RED))

                if (reloader!!.shouldForceBlueGreen) {
                    broadcast(Component.text("Switching to blue/green mode", NamedTextColor.YELLOW))
                }
            }
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

package dev.paperplane.companion

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import java.io.File
import java.io.IOException
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

class BuildStatusBar(private val plugin: JavaPlugin) {
  companion object {
    private const val POLL_INTERVAL_TICKS = 5L
  }

  private val gson = Gson()
  private var pollTask: BukkitTask? = null
  // All mutable state is accessed exclusively from the main server thread
  // (Bukkit scheduler tasks and event handlers are single-threaded)
  private var lastState: String? = null

  /**
   * True while the active server's world is no longer authoritative — either the save is in
   * progress or the CLI has moved on to building/transferring and any further edits will be
   * discarded when the standby takes over. Consumed by [SaveProtectionListener] to cancel block
   * edits for the entire rebuild window.
   */
  var blockWorldEdits: Boolean = false
    private set

  private var reloader: PluginReloader? = null
  private var hotSwapper: HotSwapper? = null
  private var isReloading = false

  private val prefix: Component =
      Component.text().append(Component.text("PaperPlane ", NamedTextColor.AQUA)).build()

  fun start() {
    // Poll companion-status.json every 5 ticks (250ms)
    pollTask =
        plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { poll() },
            POLL_INTERVAL_TICKS,
            POLL_INTERVAL_TICKS,
        )
  }

  fun stop() {
    pollTask?.cancel()
  }

  private fun poll() {
    val statusFile =
        File(plugin.dataFolder.parentFile.parentFile, ".paperplane/companion-status.json")
    if (!statusFile.exists()) return
    try {
      val json = gson.fromJson(statusFile.readText(), JsonObject::class.java)
      val state = json.get("state")?.asString ?: return

      if (state == lastState) return
      lastState = state
      blockWorldEdits = state == "saving" || state == "building"

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
          performReload(json)
        }
      }
    } catch (e: IOException) {
      plugin.logger.fine("Status file poll error: ${e.message}")
    } catch (e: JsonParseException) {
      plugin.logger.fine("Status file poll error: ${e.message}")
    }
  }

  private fun performReload(json: JsonObject) {
    if (isReloading) {
      plugin.logger.fine("Reload already in progress, skipping")
      return
    }
    isReloading = true
    try {
      doReload(json)
    } finally {
      isReloading = false
    }
  }

  private fun doReload(json: JsonObject) {
    val pluginName = json.get("pluginName")?.asString ?: return
    val jarFileName = json.get("jarFileName")?.asString ?: return

    val pluginsDir = plugin.dataFolder.parentFile.absoluteFile
    val currentJar = File(pluginsDir, jarFileName)
    val pendingJarName = json.get("pendingJar")?.asString
    val pendingJar = pendingJarName?.let { File(it) }
    val ppDir = File(plugin.dataFolder.parentFile.parentFile, ".paperplane")
    ppDir.mkdirs()

    broadcast(Component.text("Reloading $pluginName...", NamedTextColor.GOLD))

    if (reloader == null) {
      reloader = PluginReloader(plugin.server, plugin.logger)
    }

    if (reloader!!.shouldForceBlueGreen) {
      writeFlagFile(ppDir, "reload-failed", "CLASSLOADER_LEAKS")
      broadcast(Component.text("Switching to blue/green mode", NamedTextColor.YELLOW))
      return
    }

    // Determine reload strategy from protocol
    val strategy = json.get("reloadStrategy")?.asString ?: "jar"
    val buildOutputDirs =
        try {
          json.getAsJsonArray("buildOutputDirs")?.map { it.asString }
        } catch (_: JsonParseException) {
          null
        }

    val changedClasses =
        try {
          json.getAsJsonArray("changedClasses")?.map { it.asString }
        } catch (_: JsonParseException) {
          null
        }

    val outcome =
        executeReloadStrategy(
            strategy,
            pluginName,
            buildOutputDirs,
            currentJar,
            pendingJar,
            changedClasses,
        )
    val timingInfo =
        "teardown=${outcome.teardownMs},load=${outcome.loadMs},total=${outcome.totalMs}"

    when (outcome.result) {
      ReloadResult.SUCCESS -> {
        // Finalize: replace original jar with the staged .new jar (if JAR-based)
        if (pendingJar != null && pendingJar.exists()) {
          try {
            java.nio.file.Files.move(
                pendingJar.toPath(),
                currentJar.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
          } catch (e: IOException) {
            plugin.logger.warning("Failed to finalize staged jar: ${e.message}")
          }
        }
        writeFlagFile(ppDir, "reload-complete", timingInfo)
        val msg =
            if (outcome.teardownMs == 0L && outcome.loadMs == 0L) "$pluginName hot-swapped!"
            else "$pluginName reloaded!"
        broadcast(Component.text(msg, NamedTextColor.GREEN))
      }
      ReloadResult.ROLLBACK_SUCCESS -> {
        deletePendingJar(pendingJar)
        writeFlagFile(ppDir, "reload-failed", "ROLLBACK_SUCCESS")
        broadcast(
            Component.text("Reload failed, reverted to previous version", NamedTextColor.YELLOW)
        )
      }
      ReloadResult.ROLLBACK_FAILED -> {
        deletePendingJar(pendingJar)
        writeFlagFile(ppDir, "reload-failed", "ROLLBACK_FAILED")
        broadcast(
            Component.text(
                "Reload failed and rollback failed — restart recommended",
                NamedTextColor.RED,
            )
        )
        broadcast(Component.text("Switching to blue/green mode", NamedTextColor.YELLOW))
      }
      else -> {
        deletePendingJar(pendingJar)
        writeFlagFile(ppDir, "reload-failed", outcome.result.name)
        broadcast(Component.text("Reload failed: ${outcome.result}", NamedTextColor.RED))

        if (reloader!!.shouldForceBlueGreen) {
          broadcast(Component.text("Switching to blue/green mode", NamedTextColor.YELLOW))
        }
      }
    }
  }

  /**
   * Executes the tiered reload strategy with fallback cascade:
   * 1. hotswap (Level 2) — if available and requested
   * 2. directory (Level 1) — if buildOutputDirs provided
   * 3. jar (existing) — ultimate fallback
   */
  private fun executeReloadStrategy(
      strategy: String,
      pluginName: String,
      buildOutputDirs: List<String>?,
      currentJar: File,
      pendingJar: File?,
      changedClasses: List<String>? = null,
  ): ReloadOutcome {
    // Level 2: Hot-swap via instrumentation (method-body changes only)
    if (strategy == "hotswap" && buildOutputDirs != null && !changedClasses.isNullOrEmpty()) {
      if (hotSwapper == null) hotSwapper = HotSwapper(plugin.logger)
      if (hotSwapper!!.isAvailable()) {
        val targetPlugin = plugin.server.pluginManager.getPlugin(pluginName)
        if (targetPlugin != null) {
          val result =
              hotSwapper!!.redefine(
                  changedClasses,
                  targetPlugin.javaClass.classLoader,
                  buildOutputDirs,
              )
          when (result) {
            HotSwapResult.SUCCESS -> {
              return ReloadOutcome(ReloadResult.SUCCESS, totalMs = 0)
            }
            else -> {
              plugin.logger.warning(
                  "Hot-swap returned $result — falling back to classloader reload"
              )
            }
          }
        }
      }
    }

    // Level 1: Directory reload
    if (
        buildOutputDirs != null &&
            buildOutputDirs.isNotEmpty() &&
            strategy in listOf("directory", "hotswap")
    ) {
      val rollbackJar = if (currentJar.exists()) currentJar else null
      val outcome = reloader!!.reloadFromDirectory(pluginName, buildOutputDirs, rollbackJar)
      if (outcome.result == ReloadResult.SUCCESS) return outcome
      plugin.logger.warning(
          "Directory reload failed (${outcome.result}), falling back to JAR reload"
      )
    }

    // Level 0: JAR-based reload (existing behavior)
    val loadJar = pendingJar ?: currentJar
    val rollbackJar = if (pendingJar != null) currentJar else null
    return reloader!!.reload(pluginName, loadJar, rollbackJar)
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
    } catch (e: IOException) {
      plugin.logger.warning("Failed to save: ${e.message}")
    }
  }

  private fun writeFlagFile(ppDir: File, name: String, content: String) {
    try {
      File(ppDir, name).writeText(content)
    } catch (e: IOException) {
      plugin.logger.warning("Failed to write $name flag: ${e.message}")
    }
  }

  private fun deletePendingJar(pendingJar: File?) {
    if (pendingJar?.delete() == false) {
      plugin.logger.warning("Failed to delete pending jar: ${pendingJar.absolutePath}")
    }
  }

  private fun broadcast(message: Component) {
    val full = Component.text().append(prefix).append(message).build()
    for (player in plugin.server.onlinePlayers) {
      player.sendMessage(full)
    }
  }
}

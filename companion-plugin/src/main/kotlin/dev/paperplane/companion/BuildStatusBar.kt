package dev.paperplane.companion

import com.google.gson.Gson
import com.google.gson.JsonParseException
import dev.paperplane.companion.host.HostLoadRequest
import dev.paperplane.companion.host.HostLoadResult
import dev.paperplane.companion.host.InnerPluginHost
import java.io.File
import java.io.IOException
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

class BuildStatusBar(
    private val plugin: JavaPlugin,
    private val host: InnerPluginHost,
    private val serverRoot: File = plugin.dataFolder.absoluteFile.parentFile.parentFile,
) {
  companion object {
    private const val POLL_INTERVAL_TICKS = 5L
  }

  private val gson = Gson()
  private var pollTask: BukkitTask? = null
  // All mutable state is accessed exclusively from the main server thread (Bukkit scheduler tasks
  // and event handlers are single-threaded on Paper outside Folia).
  private var lastBuildState: String? = null
  private var lastRequestId: String? = null
  private var inflightRequest = false
  private var hotSwapper: HotSwapper? = null

  /**
   * True while the active server's world is no longer authoritative — either the save is in
   * progress or the CLI has moved on to building/transferring and any further edits will be
   * discarded when the standby takes over. Consumed by [SaveProtectionListener] to cancel block
   * edits for the entire rebuild window.
   */
  var blockWorldEdits: Boolean = false
    private set

  private val prefix: Component =
      Component.text().append(Component.text("PaperPlane ", NamedTextColor.AQUA)).build()

  fun start() {
    pollTask =
        plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { tick() },
            POLL_INTERVAL_TICKS,
            POLL_INTERVAL_TICKS,
        )
  }

  fun stop() {
    pollTask?.cancel()
  }

  // ── Tick ───────────────────────────────────────────────────────────

  private fun tick() {
    pollBuildStatus()
    pollLoadRequest()
  }

  // ── Build status (companion-status.json) ───────────────────────────

  private fun pollBuildStatus() {
    val statusFile = File(serverRoot, ".paperplane/companion-status.json")
    if (!statusFile.exists()) return
    try {
      val json = gson.fromJson(statusFile.readText(), com.google.gson.JsonObject::class.java)
      val state = json.get("state")?.asString ?: return
      if (state == lastBuildState) return
      lastBuildState = state
      blockWorldEdits = state == "saving" || state == "building"
      when (state) {
        "saving" -> {
          broadcast(Component.text("Saving world...", NamedTextColor.GOLD))
          performSave()
        }
        "building" -> broadcast(Component.text("Rebuilding...", NamedTextColor.YELLOW))
        "ready" -> {
          val duration = json.get("duration")?.asString ?: ""
          broadcast(Component.text("Ready $duration", NamedTextColor.GREEN))
        }
        "error" -> {
          val message = json.get("message")?.asString ?: "Build error"
          broadcast(Component.text(message, NamedTextColor.RED))
        }
      }
    } catch (e: IOException) {
      plugin.logger.fine("Status file poll error: ${e.message}")
    } catch (e: JsonParseException) {
      plugin.logger.fine("Status file poll error: ${e.message}")
    }
  }

  // ── Load request (load-request.json) ───────────────────────────────

  /** Visible for tests: drives one polling iteration without the Bukkit scheduler. */
  internal fun pollLoadRequestForTest() = pollLoadRequest()

  private fun pollLoadRequest() {
    val requestFile = File(serverRoot, ".paperplane/load-request.json")
    if (!requestFile.exists()) return
    if (inflightRequest) return

    val request: HostLoadRequest =
        try {
          gson.fromJson(requestFile.readText(), HostLoadRequest::class.java) ?: return
        } catch (e: JsonParseException) {
          plugin.logger.warning("Invalid load-request.json: ${e.message}")
          requestFile.delete()
          return
        }

    if (request.requestId == lastRequestId) return
    lastRequestId = request.requestId

    inflightRequest = true
    try {
      handleRequest(request)
    } finally {
      inflightRequest = false
      requestFile.delete()
    }
  }

  private fun handleRequest(request: HostLoadRequest) {
    broadcast(Component.text("Reloading ${request.pluginName}...", NamedTextColor.GOLD))

    // Try in-place class redefinition first if HotSwapper is available and the change is
    // method-body-only. Skips the full host reload entirely.
    if (host.isLoaded() && request.changedClasses.isNotEmpty() && request.classesDirs.isNotEmpty()) {
      if (tryHotSwap(request)) {
        writeFlag("load-complete", "hotswap=ok,total=0")
        broadcast(Component.text("${request.pluginName} hot-swapped!", NamedTextColor.GREEN))
        return
      }
    }

    val result = host.handleRequest(request)
    when (result) {
      is HostLoadResult.Ok -> {
        writeFlag(
            "load-complete",
            "total=${result.durationMs}",
        )
        val msg =
            if (host.isLoaded() && result.durationMs == 0L) "${result.pluginName} loaded!"
            else "${result.pluginName} reloaded!"
        broadcast(Component.text(msg, NamedTextColor.GREEN))
      }
      is HostLoadResult.Failed -> {
        writeFlag("load-failed", result.message)
        broadcast(Component.text("Reload failed: ${result.message}", NamedTextColor.RED))
        if (host.shouldForceBlueGreen) {
          broadcast(Component.text("Switching to blue/green mode", NamedTextColor.YELLOW))
        }
      }
    }
  }

  private fun tryHotSwap(request: HostLoadRequest): Boolean {
    val inner = host.current() ?: return false
    if (hotSwapper == null) hotSwapper = HotSwapper(plugin.logger)
    if (!hotSwapper!!.isAvailable()) return false
    val classLoader = inner.javaClass.classLoader
    val result =
        hotSwapper!!.redefine(request.changedClasses, classLoader, request.classesDirs)
    return when (result) {
      HotSwapResult.SUCCESS -> true
      else -> {
        plugin.logger.fine("Hot-swap returned $result — falling back to full host reload")
        false
      }
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────

  private fun performSave() {
    try {
      plugin.server.savePlayers()
      for (world in plugin.server.worlds) {
        world.save()
      }
      val flagFile = File(serverRoot, ".paperplane/save-complete")
      flagFile.parentFile.mkdirs()
      flagFile.writeText(System.currentTimeMillis().toString())
    } catch (e: IOException) {
      plugin.logger.warning("Failed to save: ${e.message}")
    }
  }

  private fun writeFlag(name: String, content: String) {
    try {
      val ppDir = File(serverRoot, ".paperplane").apply { mkdirs() }
      File(ppDir, name).writeText(content)
    } catch (e: IOException) {
      plugin.logger.warning("Failed to write $name flag: ${e.message}")
    }
  }

  private fun broadcast(message: Component) {
    val full = Component.text().append(prefix).append(message).build()
    for (player in plugin.server.onlinePlayers) {
      player.sendMessage(full)
    }
  }

}

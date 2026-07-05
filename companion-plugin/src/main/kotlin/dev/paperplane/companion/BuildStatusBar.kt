package dev.paperplane.companion

import com.google.gson.Gson
import com.google.gson.JsonParseException
import dev.paperplane.companion.host.HostLoadReport
import dev.paperplane.companion.host.HostLoadRequest
import dev.paperplane.companion.host.HostLoadResult
import dev.paperplane.companion.host.InnerPluginHost
import java.io.File
import java.io.IOException
import java.nio.file.Path
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
    private const val MOVE_ATTEMPTS = 3
    private const val MOVE_RETRY_DELAY_MS = 25L
  }

  private val gson = Gson()
  private var pollTask: BukkitTask? = null
  // All mutable state is accessed exclusively from the main server thread (Bukkit scheduler tasks
  // and event handlers are single-threaded on Paper outside Folia).
  private var lastBuildState: String? = null
  private var lastRequestId: String? = null
  private var inflightRequest = false
  private var hotSwapper: HotSwapper? = null
  private var agentWarningLogged = false

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

    // Claim the request by renaming it aside before parsing: the CLI's atomic replace either lands
    // before the claim (this tick handles the new request) or after it (the file survives for the
    // next tick) — a fresh request can never be deleted unseen. The lastRequestId dedup below
    // guards against re-reading a request the CLI is slow to overwrite.
    val claimed = File(serverRoot, ".paperplane/.load-request.claim")
    try {
      atomicMoveOrFallback(requestFile.toPath(), claimed.toPath())
    } catch (_: IOException) {
      return // Vanished or mid-replace — the next tick retries.
    }

    val request: HostLoadRequest =
        try {
          gson.fromJson(claimed.readText(), HostLoadRequest::class.java) ?: return
        } catch (e: JsonParseException) {
          plugin.logger.warning("Invalid load-request.json: ${e.message}")
          return
        } finally {
          claimed.delete()
        }

    if (request.requestId == lastRequestId) return
    lastRequestId = request.requestId

    inflightRequest = true
    try {
      handleRequest(request)
    } finally {
      inflightRequest = false
    }
  }

  private fun handleRequest(request: HostLoadRequest) {
    if (!agentWarningLogged && AgentAccess.instrumentation() == null) {
      plugin.logger.warning(
          "PaperPlane agent not loaded — hot-swap tier and NMS detection disabled; " +
              "full reloads still work."
      )
    }
    agentWarningLogged = true

    broadcast(Component.text("Reloading ${request.pluginName}...", NamedTextColor.GOLD))

    // Captured before dispatch so the report's strategy reflects whether this was the first load
    // (fresh) or a reload of an already-loaded plugin.
    val wasLoaded = host.isLoaded()

    // Try in-place class redefinition first if HotSwapper is available and the change is
    // method-body-only. Skips the full host reload entirely.
    if (wasLoaded && request.changedClasses.isNotEmpty() && request.classesDirs.isNotEmpty()) {
      if (tryHotSwap(request)) {
        writeReport(
            HostLoadReport(
                requestId = request.requestId,
                status = HostLoadReport.STATUS_OK,
                strategy = HostLoadReport.STRATEGY_HOTSWAP,
                durationMs = 0,
                pluginName = request.pluginName,
            )
        )
        broadcast(Component.text("${request.pluginName} hot-swapped!", NamedTextColor.GREEN))
        return
      }
    }

    val strategy = if (wasLoaded) HostLoadReport.STRATEGY_RELOAD else HostLoadReport.STRATEGY_FRESH
    val result = host.handleRequest(request)
    writeReport(reportFor(request, strategy, result))
    when (result) {
      is HostLoadResult.Ok -> {
        val msg =
            if (wasLoaded) "${result.pluginName} reloaded!" else "${result.pluginName} loaded!"
        broadcast(Component.text(msg, NamedTextColor.GREEN))
      }
      is HostLoadResult.Failed -> {
        broadcast(Component.text("Reload failed: ${result.message}", NamedTextColor.RED))
        if (host.shouldForceBlueGreen) {
          broadcast(Component.text("Switching to blue/green mode", NamedTextColor.YELLOW))
        }
      }
    }
  }

  /**
   * Maps a host result onto the wire report, echoing the request id the CLI's waiter matches on.
   * Single mapping point so a field added to [HostLoadResult] can't be carried forward in one
   * branch and dropped in the other.
   */
  private fun reportFor(
      request: HostLoadRequest,
      strategy: String,
      result: HostLoadResult,
  ): HostLoadReport =
      when (result) {
        is HostLoadResult.Ok ->
            HostLoadReport(
                requestId = request.requestId,
                status = HostLoadReport.STATUS_OK,
                strategy = strategy,
                durationMs = result.durationMs,
                pluginName = result.pluginName,
                leaks = result.leaks,
                action = result.action,
            )
        is HostLoadResult.Failed ->
            HostLoadReport(
                requestId = request.requestId,
                status = HostLoadReport.STATUS_FAILED,
                strategy = strategy,
                durationMs = result.durationMs,
                pluginName = request.pluginName,
                message = result.message,
                leaks = result.leaks,
                action = result.action,
            )
      }

  private fun tryHotSwap(request: HostLoadRequest): Boolean {
    val inner = host.current() ?: return false
    if (hotSwapper == null) hotSwapper = HotSwapper(plugin.logger)
    if (!hotSwapper!!.isAvailable()) return false
    val classLoader = inner.javaClass.classLoader
    val result = hotSwapper!!.redefine(request.changedClasses, classLoader, request.classesDirs)
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

  /**
   * Atomically writes a load result to `.paperplane/load-complete` (status ok) or `load-failed`.
   * Tmp-file + atomic move so the CLI's poll loop never observes a torn/partial JSON document.
   */
  private fun writeReport(report: HostLoadReport) {
    val name = if (report.status == HostLoadReport.STATUS_OK) "load-complete" else "load-failed"
    try {
      val ppDir = File(serverRoot, ".paperplane").apply { mkdirs() }
      val target = File(ppDir, name)
      val tmp = File(ppDir, ".$name.tmp")
      tmp.writeText(gson.toJson(report))
      moveWithRetry(tmp.toPath(), target.toPath())
    } catch (e: IOException) {
      plugin.logger.warning("Failed to write $name report: ${e.message}")
    }
  }

  /**
   * [atomicMoveOrFallback] with a short retry: on Windows the move can transiently fail with a
   * sharing violation while another process touches the target path. A dropped report would leave
   * the CLI waiting out its full timeout, so it is worth a brief retry before giving up.
   */
  private fun moveWithRetry(src: Path, dst: Path) {
    var lastError: IOException? = null
    repeat(MOVE_ATTEMPTS) { attempt ->
      try {
        atomicMoveOrFallback(src, dst)
        return
      } catch (e: IOException) {
        lastError = e
        if (attempt < MOVE_ATTEMPTS - 1) Thread.sleep(MOVE_RETRY_DELAY_MS)
      }
    }
    throw lastError!!
  }

  private fun broadcast(message: Component) {
    val full = Component.text().append(prefix).append(message).build()
    for (player in plugin.server.onlinePlayers) {
      player.sendMessage(full)
    }
  }
}

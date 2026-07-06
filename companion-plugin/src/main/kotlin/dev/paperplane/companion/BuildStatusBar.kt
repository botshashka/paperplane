package dev.paperplane.companion

import com.google.gson.Gson
import com.google.gson.JsonParseException
import dev.paperplane.companion.host.HostLoadReport
import dev.paperplane.companion.host.HostLoadRequest
import dev.paperplane.companion.host.HostLoadResult
import dev.paperplane.companion.host.InnerPluginHost
import dev.paperplane.companion.host.LeakDiagnosticsMode
import dev.paperplane.companion.host.UnsupportedPaperVersionException
import java.io.File
import java.io.IOException
import java.nio.file.Path
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

/**
 * [hostProvider] builds the [InnerPluginHost] (probing Paper internals) on first load request
 * rather than at companion startup. Native modes (restart/blue-green) never send a load request, so
 * their companion — which may run on a Paper version that predates the plugin-loader API — never
 * probes and stays fully functional (status bar, save protection, auto-op). The host is memoized on
 * first success; a failed init answers every request with `load-failed` and the companion keeps
 * running.
 */
class BuildStatusBar(
    private val plugin: JavaPlugin,
    private val hostProvider: () -> InnerPluginHost,
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

  // Lazily built by [resolveHost] on the first load request. Null until then (native modes never
  // reach it). After a failed init, [hostInitError] holds the message and every subsequent request
  // is answered with load-failed instead of re-probing.
  private var host: InnerPluginHost? = null
  private var hostInitError: String? = null

  /**
   * The host if it was ever built, for teardown. Null in native modes where no load ever arrived.
   */
  val hostOrNull: InnerPluginHost?
    get() = host

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

  /**
   * Builds the host on first use, memoizing the result. On a failed init the companion itself stays
   * up (status bar and save protection keep working even where hot-reload can't), and EVERY request
   * — not just the first — is answered with a `load-failed` echoing its own requestId: the CLI's
   * waiter filters results by requestId, so an unanswered request would surface as a generic
   * timeout instead of the real failure.
   */
  private fun resolveHost(request: HostLoadRequest): InnerPluginHost? {
    host?.let {
      return it
    }
    hostInitError?.let { message ->
      writeInitFailure(request, message)
      return null
    }
    return try {
      hostProvider().also { host = it }
    } catch (
        @Suppress("TooGenericExceptionCaught") // Anything the probe or host constructor throws must
        // reach the CLI as load-failed; escaping into the scheduler tick would leave the request
        // unanswered and the CLI staring at a timeout.
        e: Exception) {
      val message =
          when (e) {
            is UnsupportedPaperVersionException ->
                e.message ?: "Unsupported Paper version for hot-reload"
            else -> "Hot-reload host failed to initialize: ${e.javaClass.simpleName}: ${e.message}"
          }
      hostInitError = message
      plugin.logger.severe(message)
      if (e !is UnsupportedPaperVersionException) {
        plugin.logger.severe(e.stackTraceToString())
      }
      writeInitFailure(request, message)
      null
    }
  }

  private fun writeInitFailure(request: HostLoadRequest, message: String) {
    writeReport(
        HostLoadReport(
            requestId = request.requestId,
            status = HostLoadReport.STATUS_FAILED,
            strategy = HostLoadReport.STRATEGY_FRESH,
            durationMs = 0,
            pluginName = request.pluginName,
            message = message,
        )
    )
  }

  private fun handleRequest(request: HostLoadRequest) {
    val host = resolveHost(request) ?: return

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
    if (
        hotSwapEligible(
            wasLoaded,
            host.leakLimitReached,
            request.changedClasses,
            request.classesDirs,
        )
    ) {
      if (tryHotSwap(host, request)) {
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
        maybeDeferLeakDump(host, result)
      }
      is HostLoadResult.Failed ->
          broadcast(Component.text("Reload failed: ${result.message}", NamedTextColor.RED))
    }

    // The leak-limit restart signal rides out on the result's `action`: on the tripping
    // Ok (reload succeeded but leaks piled up past the limit) or, belt-and-braces, on the
    // refusal Failed sent if a prior tripping Ok was missed. Warn players before the CLI
    // pulls the server down — the CLI does the actual restart. Keying on the result, not
    // host state, means a missed Ok can't wedge the notice.
    if (result.action == HostLoadReport.ACTION_RESTART) {
      broadcast(
          Component.text("Restarting dev server to clear leaked memory...", NamedTextColor.YELLOW)
      )
    }
  }

  /**
   * Hot-swap fast-path eligibility. A method-body-only edit (changed classes, no structural change)
   * on an already-loaded plugin can be redefined in place, skipping the full host reload — but only
   * while the host hasn't tripped its leak limit. Once it has, the fast path would report Ok with
   * no `action`, deferring the leak-restart indefinitely (the CLI may have missed the tripping Ok);
   * falling through to the full reload lets the host's refusal carry the restart action. Pure so it
   * can be unit-tested without instrumentation, which [tryHotSwap] requires.
   */
  internal fun hotSwapEligible(
      wasLoaded: Boolean,
      leakLimitReached: Boolean,
      changedClasses: List<String>,
      classesDirs: List<String>,
  ): Boolean =
      wasLoaded && !leakLimitReached && changedClasses.isNotEmpty() && classesDirs.isNotEmpty()

  /**
   * Schedules the deferred leak dumps — but only in [LeakDiagnosticsMode.FULL] and only when the
   * reload actually confirmed a leak ([HostLoadResult.Ok.leaks] non-null). This runs AFTER
   * [writeReport] in [handleRequest], so the load report is already on disk: the CLI's waiter is
   * unblocked before any dump work starts, which is the whole point of deferring. The verbose
   * per-loader diagnostics run on a normal (next-tick) task; the heap dump — the slowest step —
   * runs async so it can't stall the server thread. `off`/`summary` skip both (their output already
   * happened: `summary` logged the one-line warning and rode attribution out on the report).
   */
  private fun maybeDeferLeakDump(host: InnerPluginHost, result: HostLoadResult.Ok) {
    if (host.leakDiagnostics != LeakDiagnosticsMode.FULL) return
    if (result.leaks == null) return
    plugin.server.scheduler.runTask(plugin, Runnable { host.dumpVerboseDiagnostics() })
    val heapTarget = File(serverRoot, ".paperplane/leak.hprof")
    plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { host.tryDumpHeap(heapTarget) })
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

  private fun tryHotSwap(host: InnerPluginHost, request: HostLoadRequest): Boolean {
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

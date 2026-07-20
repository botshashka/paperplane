package dev.paperplane.companion

import dev.paperplane.companion.CompanionSocketServer.StatusUpdate
import dev.paperplane.companion.host.HostLoadReport
import dev.paperplane.companion.host.HostLoadRequest
import dev.paperplane.companion.host.HostLoadResult
import dev.paperplane.companion.host.HostLoadStatus
import dev.paperplane.companion.host.HostReloadStrategy
import dev.paperplane.companion.host.InnerPluginHost
import dev.paperplane.companion.host.LeakDiagnosticsMode
import dev.paperplane.companion.host.UnsupportedPaperVersionException
import java.io.File
import java.io.IOException
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.plugin.java.JavaPlugin

/**
 * Handles the CLI's socket messages on the server main thread: `status` updates (chat broadcast,
 * save-protection window, world saves) and `load` requests (dispatch to [InnerPluginHost], answer
 * via [ipc]). `CompanionPlugin` hops incoming messages from the socket reader thread onto the main
 * thread before they reach this class, so all mutable state here is main-thread-confined.
 *
 * [hostProvider] builds the [InnerPluginHost] (probing Paper internals) on the first load request
 * rather than at companion startup. Native modes (restart/blue-green) never send a load request, so
 * their companion — which may run on a Paper version that predates the plugin-loader API — never
 * probes and stays fully functional (status broadcasts, save protection, auto-op). The host is
 * memoized on first success; a failed init answers every request with a failed report and the
 * companion keeps running.
 */
class CompanionMessageHandler(
    private val plugin: JavaPlugin,
    private val hostProvider: (LeakDiagnosticsMode) -> InnerPluginHost,
    private val ipc: CompanionIpc,
    private val serverRoot: File = plugin.dataFolder.absoluteFile.parentFile.parentFile,
) {
  companion object {
    /** Streamed stage sent when a load request is accepted for dispatch. */
    const val STAGE_LOADING = "loading"
  }

  private var hotSwapper: HotSwapper? = null
  private var agentWarningLogged = false

  // Lazily built by [resolveHost] on the first load request. Null until then (native modes never
  // reach it). After a failed init, [hostInitError] holds the message and every subsequent request
  // is answered with a failed report instead of re-probing.
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

  // ── Build status (`status` messages) ───────────────────────────────

  /**
   * Applies one CLI build-state update. The CLI sends these on state transitions only, so every
   * received update broadcasts. `saving` additionally performs the world save and answers with
   * `saveComplete`.
   */
  fun handleStatus(update: StatusUpdate) {
    blockWorldEdits = update.state == "saving" || update.state == "building"
    when (update.state) {
      "saving" -> {
        broadcast(Component.text("Saving world...", NamedTextColor.GOLD))
        performSave()
      }
      "building" -> broadcast(Component.text("Rebuilding...", NamedTextColor.YELLOW))
      "ready" -> {
        val duration = update.duration ?: ""
        broadcast(Component.text("Ready $duration", NamedTextColor.GREEN))
      }
      "error" -> {
        val message = update.message ?: "Build error"
        broadcast(Component.text(message, NamedTextColor.RED))
      }
      else -> plugin.logger.fine("Unknown status state: ${update.state}")
    }
  }

  // ── Load requests (`load` messages) ────────────────────────────────

  /**
   * Builds the host on first use, memoizing the result. On a failed init the companion itself stays
   * up (status broadcasts and save protection keep working even where hot-reload can't), and EVERY
   * request — not just the first — is answered with a failed report echoing its own requestId: the
   * CLI's waiter filters results by requestId, so an unanswered request would surface as a generic
   * timeout instead of the real failure.
   */
  private fun resolveHost(request: HostLoadRequest): InnerPluginHost? {
    host?.let {
      return it
    }
    hostInitError?.let { message ->
      sendInitFailure(request, message)
      return null
    }
    return try {
      hostProvider(LeakDiagnosticsMode.fromWire(request.leakDiagnostics)).also { host = it }
    } catch (
        @Suppress("TooGenericExceptionCaught") // Anything the probe or host constructor throws must
        // reach the CLI as a failed report; escaping into the dispatch would leave the request
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
      sendInitFailure(request, message)
      null
    }
  }

  private fun sendInitFailure(request: HostLoadRequest, message: String) {
    ipc.sendReport(
        HostLoadReport(
            requestId = request.requestId,
            status = HostLoadStatus.FAILED,
            strategy = HostReloadStrategy.FRESH,
            durationMs = 0,
            pluginName = request.pluginName,
            message = message,
        )
    )
  }

  fun handleLoadRequest(request: HostLoadRequest) {
    ipc.sendLoadProgress(request.requestId, STAGE_LOADING)
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
        ipc.sendReport(
            HostLoadReport(
                requestId = request.requestId,
                status = HostLoadStatus.OK,
                strategy = HostReloadStrategy.HOTSWAP,
                durationMs = 0,
                pluginName = request.pluginName,
            )
        )
        broadcast(Component.text("${request.pluginName} hot-swapped!", NamedTextColor.GREEN))
        return
      }
    }

    val strategy = if (wasLoaded) HostReloadStrategy.RELOAD else HostReloadStrategy.FRESH
    val result = host.handleRequest(request)
    ipc.sendReport(reportFor(request, strategy, result))
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
   * reload actually confirmed a leak ([HostLoadResult.Ok.leaks] non-null). This runs AFTER the
   * report is sent in [handleLoadRequest], so the CLI's waiter is unblocked before any dump work
   * starts, which is the whole point of deferring. The verbose per-loader diagnostics run on a
   * normal (next-tick) task; the heap dump — the slowest step — runs async so it can't stall the
   * server thread. `off`/`summary` skip both (their output already happened: `summary` logged the
   * one-line warning and rode attribution out on the report).
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
      strategy: HostReloadStrategy,
      result: HostLoadResult,
  ): HostLoadReport =
      when (result) {
        is HostLoadResult.Ok ->
            HostLoadReport(
                requestId = request.requestId,
                status = HostLoadStatus.OK,
                strategy = strategy,
                durationMs = result.durationMs,
                pluginName = result.pluginName,
                leaks = result.leaks,
                action = result.action,
            )
        is HostLoadResult.Failed ->
            HostLoadReport(
                requestId = request.requestId,
                status = HostLoadStatus.FAILED,
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
      ipc.sendSaveComplete()
    } catch (e: IOException) {
      // No saveComplete on failure — the CLI's wait times out and proceeds.
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

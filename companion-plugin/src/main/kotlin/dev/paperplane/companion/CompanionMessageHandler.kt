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
import java.util.logging.Level
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

    // Build-state wire values carried by the CLI's `status` message (mirror of the CLI's
    // CompanionWire.STATE_*; the modules share no code).
    private const val STATE_SAVING = "saving"
    private const val STATE_BUILDING = "building"
    private const val STATE_READY = "ready"
    private const val STATE_ERROR = "error"
  }

  private var instantSwapper: InstantSwapper? = null
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
    blockWorldEdits = update.state == STATE_SAVING || update.state == STATE_BUILDING
    when (update.state) {
      STATE_SAVING -> {
        broadcast(Component.text("Saving world...", NamedTextColor.GOLD))
        performSave()
      }
      STATE_BUILDING -> broadcast(Component.text("Rebuilding...", NamedTextColor.YELLOW))
      STATE_READY -> {
        val duration = update.duration ?: ""
        broadcast(Component.text("Ready $duration", NamedTextColor.GREEN))
      }
      STATE_ERROR -> {
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
          "PaperPlane agent not loaded — instant tier and NMS detection disabled; " +
              "full reloads still work."
      )
    }
    agentWarningLogged = true

    broadcast(Component.text("Reloading ${request.pluginName}...", NamedTextColor.GOLD))

    // Captured before dispatch so the report's strategy reflects whether this was the first load
    // (fresh) or a reload of an already-loaded plugin.
    val wasLoaded = host.isLoaded()
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

  // ── Instant swaps (`instantSwap` messages) ─────────────────────────

  /**
   * Applies an instant patch (in-place class redefinition) to the live plugin and answers with an
   * `instantReport`. Refusals are cheap and honest — the CLI falls through to the active mode's
   * full swap path with the reason surfaced.
   *
   * The leak-limit refusal keeps the leak-restart signal flowing: past the limit, patches would
   * report Ok with no `action`, deferring the restart indefinitely (the CLI may have missed the
   * tripping Ok); refusing routes the next change through the full reload, whose report carries
   * [HostLoadReport.ACTION_RESTART].
   */
  fun handleInstantSwap(request: HostInstantSwapRequest) {
    val start = System.currentTimeMillis()

    fun answer(
        status: HostInstantSwapStatus,
        patched: Int = 0,
        defined: Int = 0,
        appliedClasses: List<String> = emptyList(),
        reason: String? = null,
    ) {
      ipc.sendInstantReport(
          HostInstantSwapReport(
              requestId = request.requestId,
              status = status,
              patched = patched,
              defined = defined,
              appliedClasses = appliedClasses,
              reason = reason,
              durationMs = System.currentTimeMillis() - start,
          )
      )
    }

    if (host?.leakLimitReached == true) {
      answer(
          HostInstantSwapStatus.REFUSED,
          reason = "leak limit reached — a full reload must carry the restart signal",
      )
      return
    }
    val classLoader = resolveInstantLoader(request.pluginName)
    if (classLoader == null) {
      answer(HostInstantSwapStatus.REFUSED, reason = "plugin ${request.pluginName} is not loaded")
      return
    }
    val swapper =
        instantSwapper
            ?: InstantSwapper(plugin.logger, File(serverRoot, ".paperplane/instant-overlay")).also {
              instantSwapper = it
            }

    // Last-resort net for the whole patch pipeline, mirroring the load path's. The CLI blocks on a
    // report; anything that escapes here would strand it until timeout and surface as "no patch
    // answer from the companion", burying the real cause in a scheduler stack trace.
    val outcome =
        try {
          swapper.apply(request, classLoader)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
          plugin.logger.log(Level.WARNING, "Instant swap failed unexpectedly", e)
          InstantSwapper.Outcome.Failed("${e.javaClass.simpleName}: ${e.message}")
        }

    when (outcome) {
      is InstantSwapper.Outcome.Applied -> {
        answer(
            HostInstantSwapStatus.OK,
            patched = outcome.patched,
            defined = outcome.defined,
            appliedClasses = outcome.appliedClasses,
        )
        broadcast(Component.text("${request.pluginName} patched!", NamedTextColor.GREEN))
      }
      is InstantSwapper.Outcome.Refused ->
          answer(HostInstantSwapStatus.REFUSED, reason = outcome.reason)
      is InstantSwapper.Outcome.Failed ->
          answer(HostInstantSwapStatus.FAILED, reason = outcome.reason)
    }
  }

  /**
   * The live plugin's classloader: the host's inner plugin in hot-reload mode, or the natively
   * loaded plugin (Paper's own `PluginClassLoader`) in restart/blue-green.
   */
  private fun resolveInstantLoader(pluginName: String): ClassLoader? =
      host?.current()?.javaClass?.classLoader
          ?: plugin.server.pluginManager.getPlugin(pluginName)?.javaClass?.classLoader

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

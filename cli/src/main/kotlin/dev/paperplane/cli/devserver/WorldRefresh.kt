package dev.paperplane.cli.devserver

import com.google.gson.annotations.SerializedName
import dev.paperplane.cli.server.CompanionIpc

/**
 * Request from CLI to companion: "(re)load the secondary world [worldName] from its synced
 * directory." Sent as a `worldRefresh` message; answered by a [WorldReport]. The world files must
 * be fully synced before this is sent — the companion loads whatever is on disk.
 */
data class WorldRefreshRequest(val requestId: String, val worldName: String)

/**
 * Request from CLI to companion: "load-and-unload a throwaway world once." Sent as a `worldWarmup`
 * message; answered by a [WorldReport]. JIT-warms the server's world-load path so the session's
 * first real refresh doesn't pay the ~0.4 s cold penalty (gate-5 finding 5) — when to send it is
 * the caller's policy (standby warmup / image prep).
 */
data class WorldWarmupRequest(val requestId: String)

/** Terminal world-operation outcome. Mirror of the companion's `HostWorldOpStatus`. */
enum class WorldOpStatus {
  @SerializedName("ok") OK,
  @SerializedName("failed") FAILED,
}

/** Which world primitive a [WorldReport] answers. Mirror of the companion's `HostWorldOp`. */
enum class WorldOp {
  @SerializedName("refresh") REFRESH,
  @SerializedName("warmup") WARMUP,
}

/**
 * Mirror of the companion's `HostWorldReport` — same JSON shape, no cross-module dependency.
 * Arrives as a `worldReport` message. All fields default so an unexpected document shape
 * deserializes without throwing; `requestId` echoes the request so the waiter can discard stale
 * answers. [durationMs] is the companion-side cost of the operation.
 */
data class WorldReport(
    val requestId: String = "",
    val status: WorldOpStatus? = null,
    val op: WorldOp? = null,
    val worldName: String = "",
    val durationMs: Long = 0,
    val message: String? = null,
)

/** Outcome of waiting for a [WorldReport]; mirrors the load path's `LoadWaitResult` shapes. */
internal sealed interface WorldWaitResult {
  data class Answered(val report: WorldReport) : WorldWaitResult

  object TimedOut : WorldWaitResult

  object ServerExited : WorldWaitResult
}

/**
 * CLI-side driver for the world-refresh primitives: sends the request over the companion socket and
 * resolves the matching [WorldReport].
 *
 * **Ordering is load-bearing:** on a fresh server, the world refresh strictly precedes the
 * host-load of the new build — the reverse order lets `onEnable` capture `World` references the
 * refresh then invalidates. The API makes that sequence structural: a successful [refresh] is the
 * only source of a [RefreshedWorld], and Fresh-mode host-load entry points (step 6) take a
 * [RefreshedWorld] as a required parameter — you cannot reach the load call without having
 * refreshed first.
 *
 * No dev mode consumes this yet; its consumer is the Fresh swap sequence.
 */
internal class WorldRefreshFlow(private val ipc: CompanionIpc) {
  companion object {
    /**
     * Generous ceiling: a warm refresh is ~60 ms and even the JIT-cold first load ~0.5 s (gate 5);
     * the timeout only bounds a wedged server.
     */
    const val WORLD_OP_TIMEOUT_MS = 30_000L
  }

  /**
   * Proof that [worldName] finished refreshing on the target server. Constructed only by [refresh]
   * — hold one and the §2.1 ordering is already satisfied.
   */
  class RefreshedWorld internal constructor(val worldName: String, val companionDurationMs: Long)

  sealed interface RefreshResult {
    data class Ok(val world: RefreshedWorld) : RefreshResult

    /** The companion answered with a failure — [message] is its real cause, user-facing. */
    data class Failed(val message: String) : RefreshResult

    object TimedOut : RefreshResult

    object ServerExited : RefreshResult
  }

  sealed interface WarmupResult {
    data class Ok(val companionDurationMs: Long) : WarmupResult

    data class Failed(val message: String) : WarmupResult

    object TimedOut : WarmupResult

    object ServerExited : WarmupResult
  }

  /** Asks the companion to (re)load the secondary world [worldName] from its synced files. */
  fun refresh(worldName: String, timeoutMs: Long = WORLD_OP_TIMEOUT_MS): RefreshResult {
    val requestId = newRequestId()
    if (!ipc.sendWorldRefresh(WorldRefreshRequest(requestId, worldName))) {
      return RefreshResult.ServerExited
    }
    return when (val wait = ipc.awaitWorldReport(requestId, timeoutMs)) {
      is WorldWaitResult.Answered ->
          wait.report.let { report ->
            if (report.status == WorldOpStatus.OK) {
              RefreshResult.Ok(RefreshedWorld(report.worldName, report.durationMs))
            } else {
              RefreshResult.Failed(report.message ?: "world refresh failed")
            }
          }
      WorldWaitResult.TimedOut -> RefreshResult.TimedOut
      WorldWaitResult.ServerExited -> RefreshResult.ServerExited
    }
  }

  /** Asks the companion to run the throwaway-world warmup (gate-5 finding 5). */
  fun warmUp(timeoutMs: Long = WORLD_OP_TIMEOUT_MS): WarmupResult {
    val requestId = newRequestId()
    if (!ipc.sendWorldWarmup(WorldWarmupRequest(requestId))) return WarmupResult.ServerExited
    return when (val wait = ipc.awaitWorldReport(requestId, timeoutMs)) {
      is WorldWaitResult.Answered ->
          wait.report.let { report ->
            if (report.status == WorldOpStatus.OK) WarmupResult.Ok(report.durationMs)
            else WarmupResult.Failed(report.message ?: "world warmup failed")
          }
      WorldWaitResult.TimedOut -> WarmupResult.TimedOut
      WorldWaitResult.ServerExited -> WarmupResult.ServerExited
    }
  }
}

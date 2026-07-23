package dev.paperplane.cli.server

import dev.paperplane.cli.devserver.InstantSwapRequest
import dev.paperplane.cli.devserver.InstantWaitResult
import dev.paperplane.cli.devserver.LoadRequest
import dev.paperplane.cli.devserver.LoadWaitResult
import dev.paperplane.cli.devserver.instant.RedefineCapability
import dev.paperplane.cli.ipc.CompanionClient

/**
 * The CLI side of the conversation with a managed server's companion plugin — every message the dev
 * loop exchanges after startup travels through here. Split from [PaperServerManager] so process
 * lifecycle (start/stop/ready/deploy) and messaging are separate concerns: the manager owns the
 * connection's lifetime, this owns its use — and tests fake the whole conversation as one seam
 * without touching process management.
 *
 * Every send is best-effort against a possibly-absent connection: with no live client — server
 * down, or an error being reported while nothing is running — sends drop and awaits resolve as the
 * server having exited, which is the direction every caller already handles.
 */
open class CompanionIpc
internal constructor(
    private val client: () -> CompanionClient?,
    private val serverAlive: () -> Boolean,
) {

  /**
   * Pushes a build-state update to the companion (chat broadcast + save-protection window on the
   * server side).
   */
  open fun sendStatus(state: String, duration: String? = null, message: String? = null) {
    client()?.sendStatus(state, duration, message)
  }

  /** Sends a [LoadRequest] to the companion host; the caller's [awaitLoadReport] resolves it. */
  open fun sendLoadRequest(request: LoadRequest): Boolean =
      client()?.sendLoadRequest(request) ?: false

  /**
   * Waits for the companion's report answering [expectedRequestId]. Resolves
   * [LoadWaitResult.ServerExited] when the process dies or the connection drops (no client at all —
   * the server was never started — counts as exited too).
   */
  internal open fun awaitLoadReport(expectedRequestId: String, timeoutMs: Long): LoadWaitResult =
      client()?.awaitReport(expectedRequestId, timeoutMs, isAlive = serverAlive)
          ?: LoadWaitResult.ServerExited

  /**
   * The live server JVM's redefine capability from the companion's welcome handshake.
   * [RedefineCapability.NONE] when no connection is up — an unreachable server can't be patched.
   */
  internal open fun redefineCapability(): RedefineCapability =
      client()?.takeIf { it.isConnected }?.capability ?: RedefineCapability.NONE

  /** Sends an [InstantSwapRequest], best-effort like [sendLoadRequest]. */
  internal open fun sendInstantSwap(request: InstantSwapRequest): Boolean =
      client()?.sendInstantSwap(request) ?: false

  /** Waits for the instant report answering [expectedRequestId]; see [awaitLoadReport]. */
  internal open fun awaitInstantReport(
      expectedRequestId: String,
      timeoutMs: Long,
  ): InstantWaitResult =
      client()?.awaitInstantReport(expectedRequestId, timeoutMs, isAlive = serverAlive)
          ?: InstantWaitResult.ServerExited
}

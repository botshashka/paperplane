package dev.paperplane.cli.devserver

import dev.paperplane.cli.server.PaperServerManager

/** Outcome of waiting for the host to answer a [LoadRequest]. */
internal sealed interface LoadWaitResult {
  /** Host loaded/reloaded the plugin. */
  data class Ok(val report: LoadReport?) : LoadWaitResult

  /** Host rejected the load. [message] is human-readable. */
  data class Failed(val message: String, val report: LoadReport?) : LoadWaitResult

  /** No report matched the expected requestId within the timeout. */
  object TimedOut : LoadWaitResult

  /**
   * The server process died or the companion connection dropped while waiting — no result can ever
   * arrive.
   */
  object ServerExited : LoadWaitResult
}

/**
 * Waits for the host to answer a [LoadRequest]. Shared by [DevSession.startServerAndReport]
 * (initial load) and [HotReloadMode] (rebuild reloads). A `fun interface` so tests can inject a
 * scripted waiter as a lambda without a live companion connection.
 *
 * The default implementation ([socketLoadResultWaiter]) delegates to
 * [PaperServerManager.awaitLoadReport], which consumes `report` messages from the companion socket
 * — filtering on `requestId` so a stale report from a previous reload is never mistaken for this
 * one's, and resolving [LoadWaitResult.ServerExited] on process death or a dropped connection.
 */
internal fun interface LoadResultWaiter {
  fun await(
      serverManager: PaperServerManager,
      expectedRequestId: String,
      timeoutMs: Long,
  ): LoadWaitResult
}

/** The production waiter: consumes the companion socket via the server manager's connection. */
internal fun socketLoadResultWaiter(): LoadResultWaiter = LoadResultWaiter { manager, id, timeout ->
  manager.awaitLoadReport(id, timeout)
}

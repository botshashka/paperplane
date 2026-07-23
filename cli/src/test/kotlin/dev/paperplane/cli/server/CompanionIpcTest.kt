package dev.paperplane.cli.server

import dev.paperplane.cli.devserver.InstantSwapRequest
import dev.paperplane.cli.devserver.InstantWaitResult
import dev.paperplane.cli.devserver.LoadRequest
import dev.paperplane.cli.devserver.LoadWaitResult
import dev.paperplane.cli.devserver.instant.RedefineCapability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The no-connection contract: every send drops, every await resolves as the server having exited,
 * and capability reads NONE. This is what every caller sees before the first start, after a stop,
 * and in any window where the client is gone — the direction must always be "can't reach the
 * server", never a hang or a throw. (The live-connection paths are covered end-to-end by
 * `PaperServerManagerSocketTest` against a real socket.)
 */
class CompanionIpcTest {

  private val ipc = CompanionIpc(client = { null }, serverAlive = { true })

  @Test
  fun `sends without a connection drop and report undelivered`() {
    ipc.sendStatus("building") // must not throw
    assertFalse(ipc.sendLoadRequest(LoadRequest("r1", "/x.jar", "Sample")))
    assertFalse(ipc.sendInstantSwap(InstantSwapRequest("i1", "Sample")))
  }

  @Test
  fun `awaits without a connection resolve as the server having exited`() {
    assertEquals(LoadWaitResult.ServerExited, ipc.awaitLoadReport("r1", timeoutMs = 10_000))
    assertEquals(
        InstantWaitResult.ServerExited,
        ipc.awaitInstantReport("i1", timeoutMs = 10_000),
    )
  }

  @Test
  fun `capability without a connection is NONE - an unreachable server cannot be patched`() {
    assertEquals(RedefineCapability.NONE, ipc.redefineCapability())
  }
}

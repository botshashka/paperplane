package dev.paperplane.cli.server

import dev.paperplane.cli.devserver.InstantSwapRequest
import dev.paperplane.cli.devserver.InstantWaitResult
import dev.paperplane.cli.devserver.LoadRequest
import dev.paperplane.cli.devserver.LoadWaitResult
import dev.paperplane.cli.devserver.WorldRefreshRequest
import dev.paperplane.cli.devserver.WorldWaitResult
import dev.paperplane.cli.devserver.WorldWarmupRequest
import dev.paperplane.cli.devserver.instant.RedefineCapability
import dev.paperplane.cli.ipc.CompanionClient
import dev.paperplane.cli.testing.FakeCompanionSocket
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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
    assertFalse(ipc.sendWorldRefresh(WorldRefreshRequest("w1", "devworld")))
    assertFalse(ipc.sendWorldWarmup(WorldWarmupRequest("w2")))
  }

  @Test
  fun `awaits without a connection resolve as the server having exited`() {
    assertEquals(LoadWaitResult.ServerExited, ipc.awaitLoadReport("r1", timeoutMs = 10_000))
    assertEquals(
        InstantWaitResult.ServerExited,
        ipc.awaitInstantReport("i1", timeoutMs = 10_000),
    )
    assertEquals(WorldWaitResult.ServerExited, ipc.awaitWorldReport("w1", timeoutMs = 10_000))
  }

  @Test
  fun `capability without a connection is NONE - an unreachable server cannot be patched`() {
    assertEquals(RedefineCapability.NONE, ipc.redefineCapability())
  }

  @TempDir lateinit var serverDir: File

  @Test
  fun `capability from a client that lost its connection is NONE`() {
    // The post-crash window: the client object survives the drop still holding the welcome's
    // capability, and the lane may ask before anyone reconnects. The isConnected guard is what
    // keeps that stale answer from admitting a patch against a server nobody can reach.
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        assertEquals(
            CompanionClient.ConnectOutcome.Connected,
            client.connect(5_000, isAlive = { true }),
        )
        val live = CompanionIpc(client = { client }, serverAlive = { true })
        assertEquals(RedefineCapability.BODY_ONLY, live.redefineCapability())

        companion.dropConnection()
        // The read loop observes the drop asynchronously.
        val deadline = System.currentTimeMillis() + 5_000
        while (client.isConnected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(client.isConnected, "the drop must be observed before the guard is meaningful")
        assertEquals(
            RedefineCapability.NONE,
            live.redefineCapability(),
            "a stale client's capability must not outlive its connection",
        )
      }
    }
  }
}

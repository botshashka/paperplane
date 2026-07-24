package dev.paperplane.cli.devserver

import dev.paperplane.cli.server.CompanionIpc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drives [WorldRefreshFlow] against a scripted [CompanionIpc]: request wiring (fresh ids, the world
 * name on the wire), and the mapping from wait results to the typed outcomes — including that a
 * [WorldRefreshFlow.RefreshedWorld] only ever comes out of a successful refresh.
 */
class WorldRefreshFlowTest {

  /** Records world sends and answers awaits from the scripted fields. */
  private class ScriptedIpc : CompanionIpc({ null }, { false }) {
    val refreshRequests = mutableListOf<WorldRefreshRequest>()
    val warmupRequests = mutableListOf<WorldWarmupRequest>()
    var sendResult = true
    var answer: (String) -> WorldWaitResult = { WorldWaitResult.TimedOut }

    override fun sendWorldRefresh(request: WorldRefreshRequest): Boolean {
      refreshRequests += request
      return sendResult
    }

    override fun sendWorldWarmup(request: WorldWarmupRequest): Boolean {
      warmupRequests += request
      return sendResult
    }

    override fun awaitWorldReport(expectedRequestId: String, timeoutMs: Long): WorldWaitResult =
        answer(expectedRequestId)
  }

  private fun okReport(id: String, op: WorldOp, worldName: String = "devworld") =
      WorldWaitResult.Answered(
          WorldReport(
              requestId = id,
              status = WorldOpStatus.OK,
              op = op,
              worldName = worldName,
              durationMs = 61,
          )
      )

  // ── refresh ─────────────────────────────────────────────────────────

  @Test
  fun `a successful refresh yields the RefreshedWorld proof token`() {
    val ipc = ScriptedIpc()
    ipc.answer = { id -> okReport(id, WorldOp.REFRESH) }

    val result = WorldRefreshFlow(ipc).refresh("devworld")

    val ok = assertInstanceOf(WorldRefreshFlow.RefreshResult.Ok::class.java, result)
    assertEquals("devworld", ok.world.worldName)
    assertEquals(61L, ok.world.companionDurationMs)
    assertEquals("devworld", ipc.refreshRequests.single().worldName)
  }

  @Test
  fun `every refresh gets a fresh request id`() {
    val ipc = ScriptedIpc()
    ipc.answer = { id -> okReport(id, WorldOp.REFRESH) }
    val flow = WorldRefreshFlow(ipc)

    flow.refresh("devworld")
    flow.refresh("devworld")

    val (first, second) = ipc.refreshRequests
    assertTrue(first.requestId.isNotBlank())
    assertNotEquals(first.requestId, second.requestId)
  }

  @Test
  fun `a failed refresh surfaces the companion's message`() {
    val ipc = ScriptedIpc()
    ipc.answer = { id ->
      WorldWaitResult.Answered(
          WorldReport(
              requestId = id,
              status = WorldOpStatus.FAILED,
              op = WorldOp.REFRESH,
              worldName = "devworld",
              message = "no world files",
          )
      )
    }

    val result = WorldRefreshFlow(ipc).refresh("devworld")

    val failed = assertInstanceOf(WorldRefreshFlow.RefreshResult.Failed::class.java, result)
    assertEquals("no world files", failed.message)
  }

  @Test
  fun `a failed refresh without a message still fails with a readable default`() {
    val ipc = ScriptedIpc()
    ipc.answer = { id ->
      WorldWaitResult.Answered(WorldReport(requestId = id, status = WorldOpStatus.FAILED))
    }

    val failed =
        assertInstanceOf(
            WorldRefreshFlow.RefreshResult.Failed::class.java,
            WorldRefreshFlow(ipc).refresh("devworld"),
        )
    assertTrue(failed.message.isNotBlank())
  }

  @Test
  fun `a refresh timeout and a dead server map to their outcomes`() {
    val ipc = ScriptedIpc()

    ipc.answer = { WorldWaitResult.TimedOut }
    assertEquals(WorldRefreshFlow.RefreshResult.TimedOut, WorldRefreshFlow(ipc).refresh("w"))

    ipc.answer = { WorldWaitResult.ServerExited }
    assertEquals(WorldRefreshFlow.RefreshResult.ServerExited, WorldRefreshFlow(ipc).refresh("w"))
  }

  @Test
  fun `an undeliverable refresh resolves ServerExited without waiting`() {
    val ipc = ScriptedIpc()
    ipc.sendResult = false
    ipc.answer = { error("must not wait for an answer that can never come") }

    assertEquals(WorldRefreshFlow.RefreshResult.ServerExited, WorldRefreshFlow(ipc).refresh("w"))
  }

  // ── warmUp ──────────────────────────────────────────────────────────

  @Test
  fun `a successful warmup reports the companion-side duration`() {
    val ipc = ScriptedIpc()
    ipc.answer = { id -> okReport(id, WorldOp.WARMUP, worldName = "paperplane_warmup") }

    val result = WorldRefreshFlow(ipc).warmUp()

    val ok = assertInstanceOf(WorldRefreshFlow.WarmupResult.Ok::class.java, result)
    assertEquals(61L, ok.companionDurationMs)
    assertTrue(ipc.warmupRequests.single().requestId.isNotBlank())
  }

  @Test
  fun `warmup failure, timeout, death, and undeliverable sends map to their outcomes`() {
    val ipc = ScriptedIpc()

    ipc.answer = { id ->
      WorldWaitResult.Answered(
          WorldReport(requestId = id, status = WorldOpStatus.FAILED, message = "could not unload")
      )
    }
    val failed =
        assertInstanceOf(
            WorldRefreshFlow.WarmupResult.Failed::class.java,
            WorldRefreshFlow(ipc).warmUp(),
        )
    assertEquals("could not unload", failed.message)

    ipc.answer = { WorldWaitResult.TimedOut }
    assertEquals(WorldRefreshFlow.WarmupResult.TimedOut, WorldRefreshFlow(ipc).warmUp())

    ipc.answer = { WorldWaitResult.ServerExited }
    assertEquals(WorldRefreshFlow.WarmupResult.ServerExited, WorldRefreshFlow(ipc).warmUp())

    ipc.sendResult = false
    ipc.answer = { error("must not wait for an answer that can never come") }
    assertEquals(WorldRefreshFlow.WarmupResult.ServerExited, WorldRefreshFlow(ipc).warmUp())
  }
}

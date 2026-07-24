package dev.paperplane.cli.ipc

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.paperplane.cli.devserver.WorldOp
import dev.paperplane.cli.devserver.WorldOpStatus
import dev.paperplane.cli.devserver.WorldRefreshRequest
import dev.paperplane.cli.devserver.WorldWarmupRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Replays the world-refresh session REALLY captured from `WorldRefreshE2ETest` against Paper
 * 1.21.11 — `fixtures/protocol-log-world-refresh.ndjson` (flushed save, warmup, two refreshes, one
 * failed refresh) — through the CLI's codec.
 *
 * This is the golden source of truth for the world-message wire shapes (per the repo's
 * captured-real-fixtures standard). Re-capture by running `PPL_E2E=1 ./gradlew :cli:test --tests
 * "*.WorldRefreshE2ETest"` and copying `cli/build/e2e/protocol-log.ndjson` here (and to the
 * companion module's fixtures, which replays the CLI→companion direction).
 */
class WorldRefreshReplayTest {

  private val gson = Gson()

  private data class Entry(val dir: String, val line: String)

  private fun entries(): List<Entry> =
      checkNotNull(javaClass.getResourceAsStream("/fixtures/protocol-log-world-refresh.ndjson")) {
            "captured fixture missing from test resources"
          }
          .bufferedReader()
          .readLines()
          .filter { it.isNotBlank() }
          .map {
            val obj = gson.fromJson(it, JsonObject::class.java)
            Entry(obj.get("dir").asString, obj.get("line").asString)
          }

  @Test
  fun `parses real world-refresh event stream shape`() {
    val events = entries().filter { it.dir == "recv" }.map { CompanionWire.decode(it.line) }
    events.forEachIndexed { i, event -> assertNotNull(event, "recv line $i failed to decode") }

    // The session's semantic arc: welcome, explicit readiness, the flushed save's completion,
    // then the world reports — warmup ok, refresh ok twice, refresh failed (never-synced world).
    assertInstanceOf(CompanionEvent.Welcome::class.java, events.first())
    assertEquals(CompanionEvent.Ready, events[1])
    assertEquals(CompanionEvent.SaveComplete, events[2], "the flushed save must have confirmed")

    val reports = events.filterIsInstance<CompanionEvent.WorldOpReport>().map { it.report }
    assertEquals(4, reports.size)
    assertEquals(
        listOf(WorldOp.WARMUP, WorldOp.REFRESH, WorldOp.REFRESH, WorldOp.REFRESH),
        reports.map { it.op },
    )
    assertEquals(
        listOf(WorldOpStatus.OK, WorldOpStatus.OK, WorldOpStatus.OK, WorldOpStatus.FAILED),
        reports.map { it.status },
    )
    assertTrue(reports.all { it.requestId.isNotEmpty() })
    // A real server distinguishes the first load from the reload that unloaded it. Anything else
    // here means the refresh silently no-op'd on the second request.
    assertEquals(
        listOf(false, false, true, false),
        reports.map { it.reloaded },
        "warmup loads, the first refresh loads, only the repeat refresh reloads",
    )

    val warmup = reports[0]
    assertEquals("paperplane_warmup", warmup.worldName)
    assertTrue(warmup.durationMs > 0, "the real warmup did real work")

    assertTrue(reports[1].worldName == "devworld" && reports[2].worldName == "devworld")
    val failed = reports[3]
    assertEquals("never_synced", failed.worldName)
    assertTrue(
        failed.message!!.contains("world sync must complete"),
        "the failure carries the companion's real cause",
    )
  }

  @Test
  fun `parses real world-request send stream shape`() {
    val sends =
        entries().filter { it.dir == "send" }.map { gson.fromJson(it.line, JsonObject::class.java) }

    // Every captured world message must round-trip into the CLI's request types with no loss.
    val warmups = sends.filter { it.get("type")?.asString == "worldWarmup" }
    assertEquals(1, warmups.size)
    assertTrue(
        gson.fromJson(warmups.single(), WorldWarmupRequest::class.java).requestId.isNotEmpty()
    )

    val refreshes = sends.filter { it.get("type")?.asString == "worldRefresh" }
    assertEquals(3, refreshes.size)
    val requests = refreshes.map { gson.fromJson(it, WorldRefreshRequest::class.java) }
    assertTrue(requests.all { it.requestId.isNotEmpty() })
    assertEquals(listOf("devworld", "devworld", "never_synced"), requests.map { it.worldName })
  }
}

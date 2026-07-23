package dev.paperplane.cli.ipc

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.paperplane.cli.devserver.InstantSwapRequest
import dev.paperplane.cli.devserver.InstantSwapStatus
import dev.paperplane.cli.devserver.InstantWaitResult
import dev.paperplane.cli.devserver.LoadRequest
import dev.paperplane.cli.devserver.LoadStatus
import dev.paperplane.cli.devserver.LoadWaitResult
import dev.paperplane.cli.devserver.ReloadStrategy
import dev.paperplane.cli.devserver.instant.RedefineCapability
import dev.paperplane.cli.testing.FakeCompanionSocket
import java.io.File
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Replays a REAL captured protocol log — `fixtures/protocol-log-real-session.ndjson`, the
 * `dev.protocol-log` tee of an actual `ppl dev` session against Paper 1.21.4 (boot → initial fresh
 * load, then two structural-edit full reloads each followed by a body-edit instant patch, then a
 * comment-only edit's no-change rebuild) — through the CLI's codec and client.
 *
 * This is the golden source of truth for the wire shape (per the repo's captured-real-fixtures
 * standard): hand-rolled fixtures elsewhere test edge cases, but THIS file pins what the companion
 * actually emits. Re-capture by running a dev session with `dev.protocol-log: true` and copying
 * `.paperplane/server/.paperplane/protocol-log.ndjson` here (and to the companion module's
 * fixtures, which replays the CLI→companion direction).
 */
class ProtocolLogReplayTest {

  @TempDir lateinit var serverDir: File

  private val gson = Gson()

  private data class Entry(val dir: String, val line: String)

  private fun entries(): List<Entry> =
      checkNotNull(javaClass.getResourceAsStream("/fixtures/protocol-log-real-session.ndjson")) {
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
  fun `parses real companion event stream shape`() {
    val events = entries().filter { it.dir == "recv" }.map { CompanionWire.decode(it.line) }

    // Every line the real companion emitted must decode — an undecodable line here means the
    // codec drifted from the wire.
    events.forEachIndexed { i, event -> assertNotNull(event, "recv line $i failed to decode") }

    // The session's semantic arc: handshake welcome (with the JVM's redefine capabilities),
    // explicit readiness, then a fresh load, a full reload, and an instant patch.
    val welcome = assertInstanceOf(CompanionEvent.Welcome::class.java, events.first())
    assertEquals(CompanionSocketFile.PROTOCOL_VERSION, welcome.protocolVersion)
    assertEquals(false, welcome.serverReady, "the CLI connected before ServerLoadEvent")
    assertEquals(
        RedefineCapability.BODY_ONLY,
        welcome.capability,
        "the LaunchSpec always attaches the agent — welcome must say so",
    )
    assertEquals(CompanionEvent.Ready, events[1], "readiness arrived as an explicit event")

    val reports = events.filterIsInstance<CompanionEvent.Report>().map { it.report }
    assertEquals(3, reports.size)
    assertEquals(
        listOf(ReloadStrategy.FRESH, ReloadStrategy.RELOAD, ReloadStrategy.RELOAD),
        reports.map { it.strategy },
        "the session exercised both load strategies",
    )
    assertTrue(reports.all { it.status == LoadStatus.OK })
    assertTrue(reports.all { it.requestId.isNotEmpty() })
    assertTrue(reports.all { it.pluginName == "smoketest" })

    val instantReports = events.filterIsInstance<CompanionEvent.InstantReport>().map { it.report }
    assertEquals(2, instantReports.size, "the session exercised two instant patches")
    for (instant in instantReports) {
      assertEquals(InstantSwapStatus.OK, instant.status)
      assertTrue(instant.patched >= 1, "each body edit patched at least one class")
      assertTrue(instant.requestId.isNotEmpty())
    }

    val progress = events.filterIsInstance<CompanionEvent.LoadProgress>()
    assertEquals(3, progress.size, "each real load streamed a loading stage")
    assertTrue(progress.all { it.stage == "loading" })
    assertEquals(
        reports.map { it.requestId },
        progress.map { it.requestId },
        "each progress event belongs to its cycle's request",
    )
  }

  @Test
  fun `parses real CLI send stream shape`() {
    val sends =
        entries().filter { it.dir == "send" }.map { gson.fromJson(it.line, JsonObject::class.java) }

    // hello: token + version, exactly what the companion's handshake validates.
    val hello = sends.first()
    assertEquals("hello", hello.get("type").asString)
    assertEquals(CompanionSocketFile.PROTOCOL_VERSION, hello.get("protocolVersion").asInt)
    assertTrue(hello.get("token").asString.isNotEmpty())

    // Every captured load message must round-trip into the CLI's LoadRequest with no field loss.
    val loads = sends.filter { it.get("type")?.asString == "load" }
    assertEquals(3, loads.size)
    for (load in loads) {
      val request = gson.fromJson(load, LoadRequest::class.java)
      assertTrue(request.requestId.isNotEmpty())
      assertTrue(request.jarPath.endsWith("smoketest-1.0.0.jar"))
      assertEquals("smoketest", request.pluginName)
      assertTrue(request.classesDirs.isNotEmpty(), "real hot-reload sessions carry classesDirs")
      assertEquals("summary", request.leakDiagnostics)
      assertFalse(load.has("changedClasses"), "v4 removed changedClasses from the load request")
    }

    // The instant patch carried real class bytes and the expected loaded CRC.
    val instantSwaps = sends.filter { it.get("type")?.asString == "instantSwap" }
    assertEquals(2, instantSwaps.size)
    for (swapJson in instantSwaps) {
      val swap = gson.fromJson(swapJson, InstantSwapRequest::class.java)
      assertTrue(swap.requestId.isNotEmpty())
      assertEquals("smoketest", swap.pluginName)
      assertTrue(swap.classes.isNotEmpty(), "each body edit sent at least one class payload")
      for (entry in swap.classes) {
        assertTrue(entry.fqcn.isNotEmpty())
        assertTrue(entry.expectedCrc32 != 0L, "patches carry the expected loaded CRC")
        assertTrue(
            Base64.getDecoder().decode(entry.data).isNotEmpty(),
            "class bytes travel base64-encoded on the wire",
        )
      }
    }

    // The status stream: post-load ready, then building→ready per rebuild (reload, instant,
    // reload, instant, no-change).
    val states =
        sends.filter { it.get("type")?.asString == "status" }.map { it.get("state").asString }
    assertEquals(
        listOf(
            "ready",
            "building",
            "ready",
            "building",
            "ready",
            "building",
            "ready",
            "building",
            "ready",
            "building",
            "ready",
        ),
        states,
    )
  }

  @Test
  fun `a live client replaying the captured session resolves every wait like the real one did`() {
    val recvLines = entries().filter { it.dir == "recv" }.map { it.line }
    // The reports in capture order, used to drive the awaits with the session's real requestIds.
    val reportIds =
        recvLines
            .mapNotNull { CompanionWire.decode(it) as? CompanionEvent.Report }
            .map { it.report.requestId }
    val instantIds =
        recvLines
            .mapNotNull { CompanionWire.decode(it) as? CompanionEvent.InstantReport }
            .map { it.report.requestId }

    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        assertEquals(
            CompanionClient.ConnectOutcome.Connected,
            client.connect(5_000, isAlive = { true }),
        )
        companion.awaitConnection()
        // Replay everything the companion streamed after its handshake welcome (the fake sends
        // its own welcome).
        for (line in recvLines.drop(1)) companion.send(line)

        assertTrue(
            client.awaitServerReady(5_000) { true },
            "the captured ready event must resolve the readiness wait",
        )
        for (id in reportIds) {
          val result = client.awaitReport(id, 5_000) { true }
          assertInstanceOf(
              LoadWaitResult.Ok::class.java,
              result,
              "captured report $id must resolve Ok on replay",
          )
        }
        for (id in instantIds) {
          val result = client.awaitInstantReport(id, 5_000) { true }
          assertInstanceOf(
              InstantWaitResult.Answered::class.java,
              result,
              "captured instant report $id must resolve on replay",
          )
        }
      }
    }
  }
}

package dev.paperplane.cli.ipc

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.paperplane.cli.devserver.LoadRequest
import dev.paperplane.cli.devserver.LoadStatus
import dev.paperplane.cli.devserver.LoadWaitResult
import dev.paperplane.cli.devserver.ReloadStrategy
import dev.paperplane.cli.testing.FakeCompanionSocket
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Replays a REAL captured protocol log — `fixtures/protocol-log-real-session.ndjson`, the
 * `dev.protocol-log` tee of an actual `ppl dev` session against Paper 1.21.4 (boot → initial fresh
 * load → structural-edit full reload → body-edit hotswap) — through the CLI's codec and client.
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

    // The session's semantic arc: handshake welcome, explicit readiness, then three load cycles
    // (fresh → reload → hotswap), each streaming a loading stage before its report.
    val welcome = assertInstanceOf(CompanionEvent.Welcome::class.java, events.first())
    assertEquals(CompanionSocketFile.PROTOCOL_VERSION, welcome.protocolVersion)
    assertEquals(false, welcome.serverReady, "the CLI connected before ServerLoadEvent")
    assertEquals(CompanionEvent.Ready, events[1], "readiness arrived as an explicit event")

    val reports = events.filterIsInstance<CompanionEvent.Report>().map { it.report }
    assertEquals(3, reports.size)
    assertEquals(
        listOf(ReloadStrategy.FRESH, ReloadStrategy.RELOAD, ReloadStrategy.HOTSWAP),
        reports.map { it.strategy },
        "the session exercised all three strategies",
    )
    assertTrue(reports.all { it.status == LoadStatus.OK })
    assertTrue(reports.all { it.requestId.isNotEmpty() })
    assertTrue(reports.all { it.pluginName == "Smoketest" })

    val progress = events.filterIsInstance<CompanionEvent.LoadProgress>()
    assertEquals(3, progress.size)
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
      assertEquals("Smoketest", request.pluginName)
      assertTrue(request.classesDirs.isNotEmpty(), "real hot-reload sessions carry classesDirs")
      assertEquals("summary", request.leakDiagnostics)
    }
    // The reload cycles (not the initial load) carried the changed class for the hotswap ladder.
    assertEquals(
        listOf(
            emptyList(),
            listOf("me.dev.smoketest.Smoketest"),
            listOf("me.dev.smoketest.Smoketest"),
        ),
        loads.map { load -> load.getAsJsonArray("changedClasses").map { it.asString } },
    )

    // The status stream: post-load ready, then building→ready per rebuild.
    val states =
        sends.filter { it.get("type")?.asString == "status" }.map { it.get("state").asString }
    assertEquals(listOf("ready", "building", "ready", "building", "ready"), states)
  }

  @Test
  fun `a live client replaying the captured session resolves every wait like the real one did`() {
    val recvLines = entries().filter { it.dir == "recv" }.map { it.line }
    // The reports in capture order, used to drive awaitReport with the session's real requestIds.
    val reportIds =
        recvLines
            .mapNotNull { CompanionWire.decode(it) as? CompanionEvent.Report }
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
      }
    }
  }
}

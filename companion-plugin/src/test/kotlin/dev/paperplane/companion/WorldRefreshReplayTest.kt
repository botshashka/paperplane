package dev.paperplane.companion

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Replays the CLI→companion direction of the world-refresh session REALLY captured from the CLI
 * module's `WorldRefreshE2ETest` against Paper 1.21.11 —
 * `fixtures/protocol-log-world-refresh.ndjson` — into a live [CompanionSocketServer] over TCP. Pins
 * the companion's parse of what the real CLI actually sends for the world primitives; the CLI
 * module's twin replays the companion→CLI direction. The captured hello is re-tokened (each server
 * instance generates a fresh auth token) but otherwise sent verbatim.
 */
class WorldRefreshReplayTest {

  @TempDir lateinit var serverRoot: File

  private val gson = Gson()

  private fun capturedSends(): List<String> =
      checkNotNull(javaClass.getResourceAsStream("/fixtures/protocol-log-world-refresh.ndjson")) {
            "captured fixture missing from test resources"
          }
          .bufferedReader()
          .readLines()
          .filter { it.isNotBlank() }
          .map { gson.fromJson(it, JsonObject::class.java) }
          .filter { it.get("dir").asString == "send" }
          .map { it.get("line").asString }

  @Test
  fun `the companion parses and dispatches a real world-refresh session verbatim`() {
    val statuses = CopyOnWriteArrayList<CompanionSocketServer.StatusUpdate>()
    val refreshes = CopyOnWriteArrayList<HostWorldRefreshRequest>()
    val warmups = CopyOnWriteArrayList<HostWorldWarmupRequest>()
    val server =
        CompanionSocketServer(
            Logger.getLogger("replay"),
            onStatus = { statuses += it },
            onLoadRequest = {},
            onWorldRefresh = { refreshes += it },
            onWorldWarmup = { warmups += it },
        )
    try {
      server.start(serverRoot)
      val info =
          gson.fromJson(
              File(serverRoot, ".paperplane/companion-socket.json").readText(),
              JsonObject::class.java,
          )
      val socket = Socket()
      socket.connect(
          InetSocketAddress(InetAddress.getLoopbackAddress(), info.get("port").asInt),
          2_000,
      )
      socket.use {
        it.soTimeout = 5_000
        val writer = BufferedWriter(OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8))
        val reader = InputStreamReader(it.getInputStream(), Charsets.UTF_8).buffered()

        val sends = capturedSends()
        // The captured hello, re-tokened for this server instance's fresh auth token.
        val hello =
            gson.fromJson(sends.first(), JsonObject::class.java).apply {
              addProperty("token", info.get("token").asString)
            }
        writer.write(hello.toString())
        writer.write("\n")
        writer.flush()
        val welcome = gson.fromJson(reader.readLine(), JsonObject::class.java)
        assertEquals("welcome", welcome.get("type").asString)

        for (line in sends.drop(1)) {
          writer.write(line)
          writer.write("\n")
        }
        writer.flush()

        awaitTrue("all world messages dispatched") {
          statuses.size == 1 && warmups.size == 1 && refreshes.size == 3
        }
      }

      assertEquals("saving", statuses.single().state, "the real session triggered a flushed save")
      assertTrue(warmups.single().requestId.isNotEmpty())
      assertEquals(
          listOf("devworld", "devworld", "never_synced"),
          refreshes.map { it.worldName },
          "the captured refreshes must arrive with their world names intact",
      )
      assertTrue(refreshes.all { it.requestId.isNotEmpty() })
    } finally {
      server.close()
    }
  }

  private fun awaitTrue(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000
    while (!condition()) {
      if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting: $what")
      Thread.sleep(10)
    }
  }
}

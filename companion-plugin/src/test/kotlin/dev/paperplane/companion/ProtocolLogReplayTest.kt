package dev.paperplane.companion

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.paperplane.companion.CompanionSocketServer.StatusUpdate
import dev.paperplane.companion.host.HostLoadRequest
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
 * Replays the CLI→companion direction of a REAL captured protocol log —
 * `fixtures/protocol-log-real-session.ndjson`, the `dev.protocol-log` tee of an actual `ppl dev`
 * session against Paper 1.21.4 — into a live [CompanionSocketServer] over TCP.
 *
 * This pins the companion's parse of what the real CLI actually sends (the repo's
 * captured-real-fixtures standard); the CLI module's twin test replays the companion→CLI direction.
 * The captured hello is re-tokened (each server instance generates a fresh auth token) but
 * otherwise sent verbatim.
 */
class ProtocolLogReplayTest {

  @TempDir lateinit var serverRoot: File

  private val gson = Gson()

  private fun capturedSends(): List<String> =
      checkNotNull(javaClass.getResourceAsStream("/fixtures/protocol-log-real-session.ndjson")) {
            "captured fixture missing from test resources"
          }
          .bufferedReader()
          .readLines()
          .filter { it.isNotBlank() }
          .map { gson.fromJson(it, JsonObject::class.java) }
          .filter { it.get("dir").asString == "send" }
          .map { it.get("line").asString }

  @Test
  fun `the companion parses and dispatches a real CLI session verbatim`() {
    val statuses = CopyOnWriteArrayList<StatusUpdate>()
    val loads = CopyOnWriteArrayList<HostLoadRequest>()
    val server =
        CompanionSocketServer(
            Logger.getLogger("replay"),
            onStatus = { statuses += it },
            onLoadRequest = { loads += it },
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

        // Everything after the hello, byte-for-byte as the real CLI sent it.
        for (line in sends.drop(1)) {
          writer.write(line)
          writer.write("\n")
        }
        writer.flush()

        val deadline = System.currentTimeMillis() + 5_000
        while ((statuses.size < 5 || loads.size < 3) && System.currentTimeMillis() < deadline) {
          Thread.sleep(10)
        }
      }

      // The session's CLI-side arc: post-load ready, then building→ready per rebuild.
      assertEquals(
          listOf("ready", "building", "ready", "building", "ready"),
          statuses.map { it.state },
      )
      assertEquals("14.6s", statuses.first().duration, "the real ready status carried a duration")

      // Three load requests: initial (no changed classes) then two edits carrying the diff.
      assertEquals(3, loads.size)
      assertTrue(loads.all { it.pluginName == "Smoketest" })
      assertTrue(loads.all { it.requestId.isNotEmpty() })
      assertTrue(loads.all { it.jarPath.endsWith("smoketest-1.0.0.jar") })
      assertTrue(loads.all { it.classesDirs.isNotEmpty() })
      assertTrue(loads.all { it.leakDiagnostics == "summary" })
      assertEquals(
          listOf(
              emptyList(),
              listOf("me.dev.smoketest.Smoketest"),
              listOf("me.dev.smoketest.Smoketest"),
          ),
          loads.map { it.changedClasses },
      )
    } finally {
      server.close()
    }
  }
}

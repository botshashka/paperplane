package dev.paperplane.companion

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.paperplane.companion.CompanionSocketServer.StatusUpdate
import dev.paperplane.companion.host.HostLoadReport
import dev.paperplane.companion.host.HostLoadRequest
import dev.paperplane.companion.host.HostLoadStatus
import dev.paperplane.companion.host.HostReloadStrategy
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Exercises [CompanionSocketServer] against a real localhost TCP client speaking the CLI's wire
 * protocol (hand-encoded NDJSON — the CLI module's codec is mirrored, not shared). Covers the
 * handshake (token/version enforcement, welcome snapshot), message dispatch, event sends,
 * single-client replacement, and handshake-file lifecycle.
 */
class CompanionSocketServerTest {

  @TempDir lateinit var serverRoot: File

  private val gson = Gson()
  private val statuses = CopyOnWriteArrayList<StatusUpdate>()
  private val loadRequests = CopyOnWriteArrayList<HostLoadRequest>()
  private lateinit var server: CompanionSocketServer
  private val openSockets = mutableListOf<Socket>()

  @BeforeEach
  fun setUp() {
    server =
        CompanionSocketServer(
            Logger.getLogger("test"),
            onStatus = { statuses += it },
            onLoadRequest = { loadRequests += it },
        )
  }

  @AfterEach
  fun tearDown() {
    openSockets.forEach { runCatching { it.close() } }
    server.close()
  }

  private fun socketFile(): File = File(serverRoot, ".paperplane/companion-socket.json")

  private fun handshakeInfo(): JsonObject =
      gson.fromJson(socketFile().readText(), JsonObject::class.java)

  private class Client(socket: Socket) {
    val socket = socket
    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

    fun send(line: String) {
      writer.write(line)
      writer.write("\n")
      writer.flush()
    }

    fun readLine(): String? = reader.readLine()
  }

  /** Connects and completes a valid hello/welcome handshake; returns the client + welcome. */
  private fun connect(
      token: String = handshakeInfo().get("token").asString
  ): Pair<Client, JsonObject> {
    val port = handshakeInfo().get("port").asInt
    val socket = Socket()
    socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
    socket.soTimeout = 5_000
    openSockets += socket
    val client = Client(socket)
    client.send(
        """{"type":"hello","token":"$token","protocolVersion":${CompanionSocketServer.PROTOCOL_VERSION}}"""
    )
    val welcomeLine = client.readLine()
    val welcome =
        if (welcomeLine == null) JsonObject()
        else gson.fromJson(welcomeLine, JsonObject::class.java)
    return client to welcome
  }

  private fun awaitTrue(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000
    while (!condition()) {
      if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting: $what")
      Thread.sleep(10)
    }
  }

  // ── start + handshake file ──────────────────────────────────────────

  @Test
  fun `start binds an ephemeral loopback port and writes the handshake file`() {
    val port = server.start(serverRoot)

    assertTrue(port in 1..65535)
    val info = handshakeInfo()
    assertEquals(port, info.get("port").asInt)
    assertEquals(CompanionSocketServer.PROTOCOL_VERSION, info.get("protocolVersion").asInt)
    assertTrue(info.get("token").asString.isNotEmpty(), "handshake file must carry the auth token")
  }

  @Test
  fun `close removes the handshake file so nothing can dial a dead port`() {
    server.start(serverRoot)
    assertTrue(socketFile().exists())

    server.close()

    assertFalse(socketFile().exists())
  }

  @Test
  fun `tokens are unique per server instance`() {
    server.start(serverRoot)
    val first = handshakeInfo().get("token").asString
    server.close()

    val second = CompanionSocketServer(Logger.getLogger("test"), onStatus = {}, onLoadRequest = {})
    try {
      second.start(serverRoot)
      assertFalse(first == handshakeInfo().get("token").asString)
    } finally {
      second.close()
    }
  }

  // ── Handshake enforcement ───────────────────────────────────────────

  @Test
  fun `a valid hello is answered with a welcome carrying the readiness snapshot`() {
    server.start(serverRoot)
    val (_, welcome) = connect()

    assertEquals("welcome", welcome.get("type").asString)
    assertEquals(CompanionSocketServer.PROTOCOL_VERSION, welcome.get("protocolVersion").asInt)
    assertFalse(welcome.get("serverReady").asBoolean, "server not marked ready yet")
  }

  @Test
  fun `a client connecting after markServerReady sees serverReady in the welcome`() {
    server.start(serverRoot)
    server.markServerReady()

    val (_, welcome) = connect()

    assertTrue(
        welcome.get("serverReady").asBoolean,
        "a late-connecting CLI must learn the server is already ready from the snapshot",
    )
  }

  @Test
  fun `a wrong token is dropped without a welcome`() {
    server.start(serverRoot)
    val (client, _) =
        runCatching { connect(token = "wrong-token") }
            .getOrElse {
              return
            }
    assertNull(client.readLine(), "connection must be closed without a welcome")
  }

  @Test
  fun `a wrong protocol version is dropped without a welcome`() {
    server.start(serverRoot)
    val port = handshakeInfo().get("port").asInt
    val token = handshakeInfo().get("token").asString
    val socket = Socket(InetAddress.getLoopbackAddress(), port).also { openSockets += it }
    socket.soTimeout = 5_000
    val client = Client(socket)
    client.send("""{"type":"hello","token":"$token","protocolVersion":999}""")
    assertNull(client.readLine(), "version mismatch must be dropped without a welcome")
  }

  @Test
  fun `a first message that is not hello is dropped`() {
    server.start(serverRoot)
    val port = handshakeInfo().get("port").asInt
    val socket = Socket(InetAddress.getLoopbackAddress(), port).also { openSockets += it }
    socket.soTimeout = 5_000
    val client = Client(socket)
    client.send("""{"type":"status","state":"building"}""")
    assertNull(client.readLine(), "non-hello first message must be dropped")
  }

  @Test
  fun `malformed hello json is dropped without killing the accept loop`() {
    server.start(serverRoot)
    val port = handshakeInfo().get("port").asInt
    val socket = Socket(InetAddress.getLoopbackAddress(), port).also { openSockets += it }
    socket.soTimeout = 5_000
    Client(socket).send("{not json")

    // The accept loop must survive: a subsequent valid handshake succeeds.
    val (_, welcome) = connect()
    assertEquals("welcome", welcome.get("type").asString)
  }

  @Test
  fun `a non-numeric protocol version is dropped without killing the accept loop`() {
    server.start(serverRoot)
    val port = handshakeInfo().get("port").asInt
    val token = handshakeInfo().get("token").asString
    val socket = Socket(InetAddress.getLoopbackAddress(), port).also { openSockets += it }
    socket.soTimeout = 5_000
    val client = Client(socket)
    // A string protocolVersion would make Gson's asInt throw NumberFormatException. The accept loop
    // must drop just this connection — never die and leave the companion permanently undialable.
    client.send("""{"type":"hello","token":"$token","protocolVersion":"three"}""")
    assertNull(client.readLine(), "a non-numeric version must be dropped without a welcome")

    val (_, welcome) = connect()
    assertEquals("welcome", welcome.get("type").asString, "the accept loop must have survived")
  }

  @Test
  fun `a client that never sends a hello is dropped on the handshake timeout`() {
    server.start(serverRoot)
    val port = handshakeInfo().get("port").asInt
    val silent = Socket(InetAddress.getLoopbackAddress(), port).also { openSockets += it }
    silent.soTimeout = 15_000 // Longer than the server's handshake timeout so the read sees EOF.
    val reader = BufferedReader(InputStreamReader(silent.getInputStream(), Charsets.UTF_8))
    // Send nothing: the server's handshake read must time out and close the connection rather than
    // wait forever, and the accept loop must then recover.
    assertNull(reader.readLine(), "a silent client must be dropped once the handshake times out")

    val (_, welcome) = connect()
    assertEquals("welcome", welcome.get("type").asString, "the accept loop must have recovered")
  }

  // ── Message dispatch ────────────────────────────────────────────────

  @Test
  fun `a status message is dispatched with state and extras`() {
    server.start(serverRoot)
    val (client, _) = connect()

    client.send("""{"type":"status","state":"ready","duration":"1.2s"}""")

    awaitTrue("status dispatched") { statuses.isNotEmpty() }
    assertEquals(StatusUpdate("ready", "1.2s", null), statuses.single())
  }

  @Test
  fun `a load message is dispatched as a HostLoadRequest with all fields`() {
    server.start(serverRoot)
    val (client, _) = connect()

    client.send(
        """{"type":"load","requestId":"r1","jarPath":"/x.jar","pluginName":"Sample",""" +
            """"classesDirs":["/c"],"resourcesDir":"/r","runtimeClasspath":["/lib.jar"],""" +
            """"changedClasses":["com.example.Foo"],"leakDiagnostics":"full"}"""
    )

    awaitTrue("load dispatched") { loadRequests.isNotEmpty() }
    val request = loadRequests.single()
    assertEquals("r1", request.requestId)
    assertEquals("/x.jar", request.jarPath)
    assertEquals("Sample", request.pluginName)
    assertEquals(listOf("/c"), request.classesDirs)
    assertEquals("/r", request.resourcesDir)
    assertEquals(listOf("/lib.jar"), request.runtimeClasspath)
    assertEquals(listOf("com.example.Foo"), request.changedClasses)
    assertEquals("full", request.leakDiagnostics)
  }

  @Test
  fun `a status message without a state is ignored`() {
    server.start(serverRoot)
    val (client, _) = connect()

    client.send("""{"type":"status"}""")
    client.send("""{"type":"status","state":"building"}""")

    awaitTrue("second status dispatched") { statuses.isNotEmpty() }
    assertEquals("building", statuses.single().state, "the state-less status must be dropped")
  }

  @Test
  fun `unknown message types and malformed lines are skipped without dropping the connection`() {
    server.start(serverRoot)
    val (client, _) = connect()

    client.send("""{"type":"mystery"}""")
    client.send("not json at all")
    client.send("""{"type":"status","state":"building"}""")

    awaitTrue("status after garbage dispatched") { statuses.isNotEmpty() }
    assertEquals("building", statuses.single().state)
  }

  // ── Events out ──────────────────────────────────────────────────────

  @Test
  fun `markServerReady streams an explicit ready event to the connected client`() {
    server.start(serverRoot)
    val (client, _) = connect()

    server.markServerReady()

    val line = client.readLine()
    assertNotNull(line)
    assertEquals("ready", gson.fromJson(line, JsonObject::class.java).get("type").asString)
  }

  @Test
  fun `sendReport serializes the full report with the report type tag`() {
    server.start(serverRoot)
    val (client, _) = connect()

    server.sendReport(
        HostLoadReport(
            requestId = "r1",
            status = HostLoadStatus.OK,
            strategy = HostReloadStrategy.RELOAD,
            durationMs = 42,
            pluginName = "Sample",
            action = "restart",
        )
    )

    val obj = gson.fromJson(client.readLine(), JsonObject::class.java)
    assertEquals("report", obj.get("type").asString)
    assertEquals("r1", obj.get("requestId").asString)
    assertEquals("ok", obj.get("status").asString, "status must serialize as the wire value")
    assertEquals(
        "reload",
        obj.get("strategy").asString,
        "strategy must serialize as the wire value",
    )
    assertEquals(42, obj.get("durationMs").asInt)
    assertEquals("restart", obj.get("action").asString)
  }

  @Test
  fun `sendSaveComplete and sendLoadProgress stream their events`() {
    server.start(serverRoot)
    val (client, _) = connect()

    server.sendSaveComplete()
    server.sendLoadProgress("r1", "loading")

    val first = gson.fromJson(client.readLine(), JsonObject::class.java)
    assertEquals("saveComplete", first.get("type").asString)
    val second = gson.fromJson(client.readLine(), JsonObject::class.java)
    assertEquals("loadProgress", second.get("type").asString)
    assertEquals("r1", second.get("requestId").asString)
    assertEquals("loading", second.get("stage").asString)
  }

  @Test
  fun `sends with no connected client are dropped without throwing`() {
    server.start(serverRoot)
    server.markServerReady()
    server.sendSaveComplete()
    server.sendLoadProgress("r1", "loading")
    // Nothing to assert beyond "no exception" — the CLI resolves via its own timeouts.
  }

  @Test
  fun `readiness is never lost when markServerReady races the handshake`() {
    // Regression for the lost-update race: handshake used to read the serverReady snapshot for the
    // welcome and install the client writer non-atomically, so a markServerReady() landing in the
    // gap set the flag while clientWriter was still null (the `ready` event dropped) yet after the
    // snapshot was read (the welcome said not-ready) — the signal vanished. Both are now held under
    // writeLock, so a racing client must always learn readiness: via the welcome snapshot, or the
    // streamed `ready` event that follows.
    repeat(40) { iteration ->
      val fresh = CompanionSocketServer(Logger.getLogger("test"), onStatus = {}, onLoadRequest = {})
      val root = File(serverRoot, "race-$iteration").apply { mkdirs() }
      try {
        val port = fresh.start(root)
        val token =
            gson
                .fromJson(
                    File(root, ".paperplane/companion-socket.json").readText(),
                    JsonObject::class.java,
                )
                .get("token")
                .asString

        val socket = Socket()
        socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
        socket.soTimeout = 5_000
        openSockets += socket
        val client = Client(socket)

        // Fire readiness concurrently with the hello so they interleave around the install.
        val marker = Thread { fresh.markServerReady() }
        marker.start()
        client.send(
            """{"type":"hello","token":"$token","protocolVersion":${CompanionSocketServer.PROTOCOL_VERSION}}"""
        )
        val welcome = gson.fromJson(client.readLine(), JsonObject::class.java)
        marker.join(2_000)

        val ready =
            welcome.get("serverReady").asBoolean ||
                run {
                  // Not in the snapshot → the streamed `ready` event must arrive.
                  val next = client.readLine()
                  next != null &&
                      gson.fromJson(next, JsonObject::class.java).get("type").asString == "ready"
                }
        assertTrue(ready, "iteration $iteration lost the readiness signal")
      } finally {
        fresh.close()
      }
    }
  }

  // ── Single-client policy ────────────────────────────────────────────

  @Test
  fun `a newly authenticated connection replaces the previous one`() {
    server.start(serverRoot)
    val (first, _) = connect()
    val (second, _) = connect()

    awaitTrue("first connection closed") { first.readLine() == null }

    server.markServerReady()
    val line = second.readLine()
    assertNotNull(line, "the new connection must receive events")
    assertEquals("ready", gson.fromJson(line, JsonObject::class.java).get("type").asString)
  }

  @Test
  fun `a dropped client clears the slot and a reconnect works`() {
    server.start(serverRoot)
    val (first, _) = connect()
    first.socket.close()

    val (second, welcome) = connect()
    assertEquals("welcome", welcome.get("type").asString)
    server.sendSaveComplete()
    assertNotNull(second.readLine(), "reconnected client must receive events")
  }
}

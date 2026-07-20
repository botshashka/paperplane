package dev.paperplane.cli.testing

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Test double for the companion's side of the socket protocol: binds a localhost port, writes the
 * `.paperplane/companion-socket.json` handshake file the CLI dials from, answers the CLI's `hello`
 * with a `welcome`, records every received line, and lets tests script companion→CLI events.
 *
 * Mirrors the real `CompanionSocketServer` behavior closely enough to exercise the CLI's
 * [dev.paperplane.cli.ipc.CompanionClient] end to end over real TCP.
 */
class FakeCompanionSocket(
    private val serverDir: File,
    private val token: String = "test-token",
    private val protocolVersion: Int = 3,
    private val welcomeProtocolVersion: Int = protocolVersion,
    /** When false, the fake accepts the connection but never answers the hello (handshake hang). */
    private val answerHello: Boolean = true,
    /** Snapshot the welcome reports for `serverReady`. */
    private val serverReadyOnWelcome: Boolean = false,
) : AutoCloseable {
  private val gson = Gson()
  private val serverSocket = ServerSocket()
  private val connectionReady = CountDownLatch(1)

  @Volatile private var client: Socket? = null
  @Volatile private var writer: BufferedWriter? = null

  /** Every line received from the CLI after (and including) the hello, in order. */
  val received = CopyOnWriteArrayList<String>()

  val port: Int
    get() = serverSocket.localPort

  fun start(): FakeCompanionSocket {
    serverSocket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    writeHandshakeFile()
    Thread(::acceptLoop, "fake-companion-accept").apply {
      isDaemon = true
      start()
    }
    return this
  }

  /** Writes the handshake file exactly as the real companion does. */
  fun writeHandshakeFile(portOverride: Int = serverSocket.localPort) {
    val file = File(serverDir, ".paperplane/companion-socket.json")
    file.parentFile.mkdirs()
    file.writeText(
        JsonObject()
            .apply {
              addProperty("port", portOverride)
              addProperty("token", token)
              addProperty("protocolVersion", protocolVersion)
            }
            .toString()
    )
  }

  private fun acceptLoop() {
    while (!serverSocket.isClosed) {
      val socket =
          try {
            serverSocket.accept()
          } catch (_: IOException) {
            return
          }
      try {
        handle(socket)
      } catch (_: IOException) {
        // Connection died mid-handshake — keep accepting.
      }
    }
  }

  private fun handle(socket: Socket) {
    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    val hello = reader.readLine() ?: return
    received += hello
    val helloToken = gson.fromJson(hello, JsonObject::class.java)?.get("token")?.asString
    if (!answerHello) return // Leave the CLI hanging in its handshake read.
    if (helloToken != token) {
      socket.close() // Real companion drops non-matching tokens without a welcome.
      return
    }
    out.write(
        JsonObject()
            .apply {
              addProperty("type", "welcome")
              addProperty("protocolVersion", welcomeProtocolVersion)
              addProperty("serverReady", serverReadyOnWelcome)
            }
            .toString()
    )
    out.write("\n")
    out.flush()
    client = socket
    writer = out
    connectionReady.countDown()
    while (true) {
      val line = reader.readLine() ?: break
      received += line
    }
  }

  /** Blocks until a client has completed the handshake. */
  fun awaitConnection(timeoutMs: Long = 5_000) {
    check(connectionReady.await(timeoutMs, TimeUnit.MILLISECONDS)) {
      "no client completed the handshake within ${timeoutMs}ms"
    }
  }

  /** Sends one raw NDJSON line to the connected CLI. */
  fun send(line: String) {
    val out = checkNotNull(writer) { "no connected client" }
    synchronized(out) {
      out.write(line)
      out.write("\n")
      out.flush()
    }
  }

  fun sendReady() = send("""{"type":"ready"}""")

  fun sendSaveComplete() = send("""{"type":"saveComplete"}""")

  /** Drops the established connection, simulating a companion/server crash. */
  fun dropConnection() {
    client?.close()
  }

  /** Blocks until [count] lines have been received or the timeout elapses. */
  fun awaitReceived(count: Int, timeoutMs: Long = 5_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (received.size < count) {
      check(System.currentTimeMillis() < deadline) {
        "expected $count received lines, got ${received.size}: $received"
      }
      Thread.sleep(10)
    }
  }

  override fun close() {
    runCatching { client?.close() }
    runCatching { serverSocket.close() }
  }
}

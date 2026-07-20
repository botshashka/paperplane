package dev.paperplane.companion

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import dev.paperplane.companion.host.HostLoadReport
import dev.paperplane.companion.host.HostLoadRequest
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.logging.Logger

/**
 * What the companion sends to the CLI over the socket. [CompanionSocketServer] implements it; the
 * message handler depends on this interface so tests can record sends without a live socket.
 */
interface CompanionIpc {
  /** Terminal answer to a `load` request. */
  fun sendReport(report: HostLoadReport)

  /** The world save requested by a `saving` status finished. */
  fun sendSaveComplete()

  /** Streamed load stage for [requestId] (`loading` today; Fresh mode will add more). */
  fun sendLoadProgress(requestId: String, stage: String)
}

/**
 * The companion's side of the CLI↔companion socket: a localhost TCP server bound to an ephemeral
 * port, published to the CLI via the `.paperplane/companion-socket.json` handshake file. NDJSON
 * wire format — one JSON object per line, UTF-8, discriminated by `type`. Mirror of the CLI's
 * `CompanionWire`; the modules share no code, so shape changes must land on both sides (and bump
 * [PROTOCOL_VERSION]).
 *
 * Connections authenticate with the random [token] from the handshake file — a connection whose
 * first line isn't a matching `hello` is dropped, so a stale handshake file pointing at a reused
 * port (or an unrelated local process probing it) can't talk to the companion. One client at a
 * time: a newly authenticated connection replaces the previous one, which is how the CLI re-dials
 * across its own restarts.
 *
 * Threading: [onStatus]/[onLoadRequest] fire on the connection's reader thread — the caller is
 * responsible for hopping to the server main thread. Sends are serialized on an internal lock and
 * may come from any thread. The server-readiness latch ([markServerReady]) is streamed as an
 * explicit `ready` event AND snapshotted into the welcome reply, because the CLI may connect before
 * or after `ServerLoadEvent` fires.
 */
class CompanionSocketServer(
    private val logger: Logger,
    private val onStatus: (StatusUpdate) -> Unit,
    private val onLoadRequest: (HostLoadRequest) -> Unit,
) : CompanionIpc, AutoCloseable {
  companion object {
    /** Mirror of the CLI's `CompanionSocketFile.PROTOCOL_VERSION`. */
    const val PROTOCOL_VERSION = 3
    private const val SOCKET_FILE_NAME = "companion-socket.json"
    private const val HANDSHAKE_TIMEOUT_MS = 5_000
    private const val TOKEN_BITS = 128
    private const val TOKEN_RADIX = 16
  }

  /** A decoded CLI `status` message: build state plus its optional display extras. */
  data class StatusUpdate(val state: String, val duration: String?, val message: String?)

  private val gson = Gson()
  private val token: String = BigInteger(TOKEN_BITS, SecureRandom()).toString(TOKEN_RADIX)
  private val writeLock = Any()

  private var serverSocket: ServerSocket? = null
  private var socketFile: File? = null

  // The single authenticated connection, guarded by writeLock. Replaced when a new client
  // authenticates; cleared when its reader hits EOF.
  private var client: Socket? = null
  private var clientWriter: BufferedWriter? = null

  @Volatile private var serverReady = false
  @Volatile private var closed = false

  /**
   * Binds the socket and writes the handshake file into [serverRoot]`/.paperplane/`. Must be called
   * before the CLI can discover the companion; a bind failure throws so the companion's
   * enable-failure path reports it via the bootstrap error file.
   */
  fun start(serverRoot: File): Int {
    val ss = ServerSocket()
    ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    serverSocket = ss
    val file = File(serverRoot, ".paperplane/$SOCKET_FILE_NAME")
    file.parentFile.mkdirs()
    val info =
        JsonObject().apply {
          addProperty("port", ss.localPort)
          addProperty("token", token)
          addProperty("protocolVersion", PROTOCOL_VERSION)
        }
    file.writeText(info.toString())
    socketFile = file
    Thread(::acceptLoop, "paperplane-ipc-accept").apply {
      isDaemon = true
      start()
    }
    return ss.localPort
  }

  /**
   * Latches server readiness (idempotent) and streams the explicit `ready` event. Called from the
   * `ServerLoadEvent` listener; a client connecting later gets the state via its welcome snapshot.
   */
  fun markServerReady() {
    serverReady = true
    sendLine(JsonObject().apply { addProperty("type", "ready") }.toString())
  }

  override fun sendReport(report: HostLoadReport) {
    sendLine(
        gson.toJsonTree(report).asJsonObject.apply { addProperty("type", "report") }.toString()
    )
  }

  override fun sendSaveComplete() {
    sendLine(JsonObject().apply { addProperty("type", "saveComplete") }.toString())
  }

  override fun sendLoadProgress(requestId: String, stage: String) {
    sendLine(
        JsonObject()
            .apply {
              addProperty("type", "loadProgress")
              addProperty("requestId", requestId)
              addProperty("stage", stage)
            }
            .toString()
    )
  }

  private fun acceptLoop() {
    val ss = serverSocket ?: return
    while (!closed) {
      val candidate =
          try {
            ss.accept()
          } catch (_: IOException) {
            return // Socket closed — companion shutting down.
          }
      try {
        handshake(candidate)
      } catch (e: IOException) {
        logger.fine("Companion socket handshake failed: ${e.message}")
        try {
          candidate.close()
        } catch (_: IOException) {}
      }
    }
  }

  /**
   * Authenticates one connection: the first line must be a `hello` carrying the handshake file's
   * token and a matching protocol version. Success replies with a `welcome` (including the
   * server-readiness snapshot), installs the connection as the active client — closing any previous
   * one — and starts its reader thread. Failure throws and the connection is dropped.
   */
  private fun handshake(candidate: Socket) {
    candidate.soTimeout = HANDSHAKE_TIMEOUT_MS
    val reader = BufferedReader(InputStreamReader(candidate.getInputStream(), Charsets.UTF_8))
    val writer = BufferedWriter(OutputStreamWriter(candidate.getOutputStream(), Charsets.UTF_8))
    val line = reader.readLine() ?: throw IOException("closed before hello")
    validateHello(line)?.let { reason -> throw IOException(reason) }
    val welcome =
        JsonObject().apply {
          addProperty("type", "welcome")
          addProperty("protocolVersion", PROTOCOL_VERSION)
          addProperty("serverReady", serverReady)
        }
    writer.write(welcome.toString())
    writer.write("\n")
    writer.flush()
    candidate.soTimeout = 0

    val previous: Socket?
    synchronized(writeLock) {
      previous = client
      client = candidate
      clientWriter = writer
    }
    try {
      previous?.close()
    } catch (_: IOException) {}
    Thread({ readLoop(candidate, reader) }, "paperplane-ipc-reader").apply {
      isDaemon = true
      start()
    }
  }

  /** Returns a rejection reason for an invalid hello line, or null when it authenticates. */
  private fun validateHello(line: String): String? {
    val hello =
        try {
          gson.fromJson(line, JsonObject::class.java)
        } catch (e: JsonParseException) {
          return "malformed hello: ${e.message}"
        } ?: return "empty hello"
    if (hello.get("type")?.takeIf { it.isJsonPrimitive }?.asString != "hello") {
      return "first message was not hello"
    }
    if (hello.get("token")?.takeIf { it.isJsonPrimitive }?.asString != token) {
      return "token mismatch"
    }
    val version = hello.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
    if (version != PROTOCOL_VERSION) {
      return "protocol version $version != $PROTOCOL_VERSION"
    }
    return null
  }

  /**
   * Reads and dispatches CLI messages until the connection drops. Unknown or malformed lines are
   * logged and skipped — a newer CLI must not be able to kill the companion's endpoint.
   */
  private fun readLoop(connection: Socket, reader: BufferedReader) {
    try {
      while (true) {
        val line = reader.readLine() ?: break
        dispatch(line)
      }
    } catch (_: IOException) {
      // Dropped connection — fall through to cleanup.
    }
    synchronized(writeLock) {
      if (client === connection) {
        client = null
        clientWriter = null
      }
    }
    try {
      connection.close()
    } catch (_: IOException) {}
  }

  private fun dispatch(line: String) {
    val obj =
        try {
          gson.fromJson(line, JsonObject::class.java) ?: return
        } catch (e: JsonParseException) {
          logger.warning("Invalid companion socket message: ${e.message}")
          return
        }
    when (val type = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString) {
      "status" ->
          onStatus(
              StatusUpdate(
                  state = obj.get("state")?.takeIf { it.isJsonPrimitive }?.asString ?: return,
                  duration = obj.get("duration")?.takeIf { it.isJsonPrimitive }?.asString,
                  message = obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString,
              )
          )
      "load" ->
          try {
            onLoadRequest(gson.fromJson(obj, HostLoadRequest::class.java))
          } catch (e: JsonParseException) {
            logger.warning("Invalid load request: ${e.message}")
          }
      else -> logger.fine("Unknown companion socket message type: $type")
    }
  }

  /**
   * Writes one line to the active client, best-effort: no client or a failed write drops the
   * message (and the connection on failure) — the CLI's awaits resolve via their own timeouts.
   */
  private fun sendLine(line: String) {
    synchronized(writeLock) {
      val writer = clientWriter ?: return
      try {
        writer.write(line)
        writer.write("\n")
        writer.flush()
      } catch (e: IOException) {
        logger.fine("Companion socket send failed: ${e.message}")
        try {
          client?.close()
        } catch (_: IOException) {}
        client = null
        clientWriter = null
      }
    }
  }

  /**
   * Stops accepting, drops the client, and removes the handshake file so a later process can't dial
   * a dead port. Idempotent.
   */
  override fun close() {
    closed = true
    try {
      serverSocket?.close()
    } catch (_: IOException) {}
    synchronized(writeLock) {
      try {
        client?.close()
      } catch (_: IOException) {}
      client = null
      clientWriter = null
    }
    socketFile?.delete()
  }
}

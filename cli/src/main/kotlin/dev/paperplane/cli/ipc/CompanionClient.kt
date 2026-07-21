package dev.paperplane.cli.ipc

import dev.paperplane.cli.devserver.LoadReport
import dev.paperplane.cli.devserver.LoadRequest
import dev.paperplane.cli.devserver.LoadStatus
import dev.paperplane.cli.devserver.LoadWaitResult
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The CLI's side of the companion socket. The CLI owns the connection: it dials (and, across server
 * restarts/restores, re-dials) the port the companion published via [CompanionSocketFile],
 * authenticates with the file's token, and then consumes the companion's streamed events.
 *
 * Liveness semantics (per the socket-protocol design):
 * - An **established connection** proves the companion is alive — it replaces the old
 *   `server-ready` flag file as the companion-liveness signal.
 * - A **dropped connection** is the crash signal: every await treats disconnection as the server
 *   dying, alongside the process-liveness callback the caller supplies.
 * - **Server readiness is an explicit streamed event**, never inferred from the connection — a
 *   completed TCP handshake can false-pass (the kernel backlog completes handshakes for listeners
 *   that never accept), and even an accepted connection only proves the companion enabled, not that
 *   the server finished startup.
 *
 * One client instance serves one server process run; [PaperServerManager.start] creates a fresh one
 * per launch. Thread-safe: sends are serialized on an internal lock, awaits poll queues fed by the
 * reader thread.
 */
internal class CompanionClient(
    private val serverDir: File,
    private val tee: ProtocolTee? = null,
) : AutoCloseable {
  companion object {
    private const val DIAL_POLL_INTERVAL_MS = 100L
    private const val CONNECT_ATTEMPT_TIMEOUT_MS = 500
    private const val HANDSHAKE_READ_TIMEOUT_MS = 2_000
    private const val AWAIT_POLL_INTERVAL_MS = 100L
    private const val READER_JOIN_TIMEOUT_MS = 500L
  }

  /** Outcome of [connect]'s dial loop. */
  sealed interface ConnectOutcome {
    object Connected : ConnectOutcome

    /** The companion reported a fatal enable failure via its bootstrap error file. */
    data class CompanionFailed(val message: String) : ConnectOutcome

    /** The server process died before the companion ever published a socket. */
    object Died : ConnectOutcome

    object TimedOut : ConnectOutcome
  }

  /**
   * Thrown by [performHandshake] when the companion answers with a different protocol version.
   * Distinct from a transient handshake [IOException] so [connect] can fail fast — re-dialing the
   * same stale companion jar would only hit the identical mismatch until the timeout.
   */
  private class ProtocolMismatchException(message: String) : IOException(message)

  private val lock = Any()
  private var socket: Socket? = null
  private var writer: BufferedWriter? = null
  @Volatile private var readerThread: Thread? = null

  @Volatile private var connected = false

  /**
   * True once the companion streamed `ready` (or its welcome snapshot said the server already
   * finished startup). Latched for the connection's lifetime.
   */
  @Volatile
  var serverReady: Boolean = false
    private set

  private val saveCompletions = LinkedBlockingQueue<Unit>()
  private val reports = LinkedBlockingQueue<LoadReport>()

  val isConnected: Boolean
    get() = connected

  /**
   * Dials the companion: polls for the handshake file, connects, and authenticates, retrying every
   * [DIAL_POLL_INTERVAL_MS] until [timeoutMs] elapses. [isAlive] short-circuits the wait when the
   * server process dies; [companionError] is polled for the companion's bootstrap failure message
   * (the one signal that can't travel over a socket whose owner failed to construct it).
   */
  fun connect(
      timeoutMs: Long,
      isAlive: () -> Boolean,
      companionError: () -> String? = { null },
  ): ConnectOutcome {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      companionError()?.let {
        return ConnectOutcome.CompanionFailed(it)
      }
      if (!isAlive()) return ConnectOutcome.Died
      CompanionSocketFile.read(serverDir)?.let { info ->
        attemptDial(info)?.let {
          return it
        }
      }
      Thread.sleep(DIAL_POLL_INTERVAL_MS)
    }
    return ConnectOutcome.TimedOut
  }

  /**
   * One dial attempt: [ConnectOutcome.Connected] on success, [ConnectOutcome.CompanionFailed] on a
   * fatal version mismatch (won't heal by re-dialing — surface it now so the user gets "rebuild the
   * companion jar" instead of waiting out the whole timeout), or null to keep retrying.
   */
  private fun attemptDial(info: CompanionSocketInfo): ConnectOutcome? =
      try {
        if (tryConnect(info)) ConnectOutcome.Connected else null
      } catch (e: ProtocolMismatchException) {
        ConnectOutcome.CompanionFailed(e.message ?: "companion protocol mismatch")
      }

  /**
   * One connect + handshake attempt. A transient failure — refused connection (stale port file),
   * handshake timeout (port squatted by something that isn't the companion), token rejection
   * (companion closes without a welcome) — closes the socket and reports false so the dial loop
   * retries. A [ProtocolMismatchException] is non-transient: the socket is closed and it propagates
   * so [connect] can fail fast.
   */
  private fun tryConnect(info: CompanionSocketInfo): Boolean {
    val candidate = Socket()
    return try {
      candidate.connect(
          InetSocketAddress(InetAddress.getLoopbackAddress(), info.port),
          CONNECT_ATTEMPT_TIMEOUT_MS,
      )
      candidate.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
      val out = BufferedWriter(OutputStreamWriter(candidate.getOutputStream(), Charsets.UTF_8))
      val reader = BufferedReader(InputStreamReader(candidate.getInputStream(), Charsets.UTF_8))
      val welcome = performHandshake(info, out, reader)
      candidate.soTimeout = 0
      synchronized(lock) {
        socket = candidate
        writer = out
        connected = true
      }
      if (welcome.serverReady) serverReady = true
      readerThread =
          Thread({ readLoop(reader) }, "companion-ipc-reader").apply {
            isDaemon = true
            start()
          }
      true
    } catch (e: ProtocolMismatchException) {
      try {
        candidate.close()
      } catch (_: IOException) {}
      throw e
    } catch (_: IOException) {
      try {
        candidate.close()
      } catch (_: IOException) {}
      false
    }
  }

  /**
   * Sends the hello and validates the companion's welcome. An unreadable/garbage reply throws
   * [IOException] (retryable — likely a port squatter); a decodable welcome with the wrong protocol
   * version throws [ProtocolMismatchException] (non-retryable — the companion jar is stale).
   */
  private fun performHandshake(
      info: CompanionSocketInfo,
      out: BufferedWriter,
      reader: BufferedReader,
  ): CompanionEvent.Welcome {
    val hello = CompanionWire.encodeHello(info.token)
    out.write(hello)
    out.write("\n")
    out.flush()
    tee?.record(ProtocolTee.SEND, hello)
    val line = reader.readLine() ?: throw IOException("companion closed during handshake")
    tee?.record(ProtocolTee.RECV, line)
    return parseWelcome(line)
  }

  /** Decodes and version-checks the welcome line. See [performHandshake] for the throw contract. */
  private fun parseWelcome(line: String): CompanionEvent.Welcome {
    val welcome =
        CompanionWire.decode(line) as? CompanionEvent.Welcome
            ?: throw IOException("invalid handshake reply: $line")
    if (welcome.protocolVersion != CompanionSocketFile.PROTOCOL_VERSION) {
      throw ProtocolMismatchException(
          "companion speaks protocol ${welcome.protocolVersion}, this CLI speaks " +
              "${CompanionSocketFile.PROTOCOL_VERSION} — rebuild to refresh the companion jar"
      )
    }
    return welcome
  }

  /**
   * Reader thread: decodes and dispatches companion events until EOF/error marks the connection
   * dropped. Undecodable lines are skipped (still teed) so an unknown event can't kill the session.
   */
  private fun readLoop(reader: BufferedReader) {
    try {
      while (true) {
        val line = reader.readLine() ?: break
        tee?.record(ProtocolTee.RECV, line)
        when (val event = CompanionWire.decode(line)) {
          is CompanionEvent.Ready -> serverReady = true
          is CompanionEvent.SaveComplete -> saveCompletions.put(Unit)
          is CompanionEvent.Report -> reports.put(event.report)
          // LoadProgress is a streamed stage with no consumer yet (Fresh/Instant mode will read
          // it); a Welcome outside the handshake and an unknown line are likewise ignored.
          is CompanionEvent.LoadProgress,
          is CompanionEvent.Welcome,
          null -> {}
        }
      }
    } catch (_: IOException) {
      // Fall through to the disconnect mark.
    } finally {
      connected = false
      // Release the fd promptly on EOF/crash instead of leaving it in CLOSE_WAIT until the next
      // start()/stop() calls close(). close() is idempotent, so the later close is harmless.
      synchronized(lock) {
        try {
          socket?.close()
        } catch (_: IOException) {}
      }
    }
  }

  /** Sends one raw line, best-effort. A send failure marks the connection dropped. */
  private fun send(rawLine: String): Boolean {
    synchronized(lock) {
      val out = writer ?: return false
      if (!connected) return false
      return try {
        out.write(rawLine)
        out.write("\n")
        out.flush()
        tee?.record(ProtocolTee.SEND, rawLine)
        true
      } catch (_: IOException) {
        connected = false
        false
      }
    }
  }

  fun sendStatus(state: String, duration: String? = null, message: String? = null): Boolean =
      send(CompanionWire.encodeStatus(state, duration, message))

  fun sendLoadRequest(request: LoadRequest): Boolean = send(CompanionWire.encodeLoad(request))

  /**
   * Waits for the explicit server-readiness event. Readiness already streamed (or snapshotted in
   * the welcome) resolves immediately; the readiness check runs before the liveness checks so a
   * ready-then-crashed server still reports the readiness that genuinely happened.
   */
  fun awaitServerReady(timeoutMs: Long, isAlive: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      if (serverReady) return true
      if (!connected || !isAlive()) return false
      if (System.currentTimeMillis() >= deadline) return false
      Thread.sleep(AWAIT_POLL_INTERVAL_MS)
    }
  }

  /**
   * Drops any stale `saveComplete` still queued from an earlier save. Called before requesting a
   * new save so a late event from the previous cycle can't satisfy this one's wait.
   */
  fun drainSaveCompletions() {
    saveCompletions.clear()
  }

  /**
   * Waits for the companion's `saveComplete`. Like [awaitReport], the queue is polled before the
   * liveness checks so a completion that lands exactly as the process dies still wins, and a
   * dropped connection or dead process short-circuits the wait instead of blocking the whole
   * timeout.
   */
  fun awaitSaveComplete(timeoutMs: Long, isAlive: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      val remaining = deadline - System.currentTimeMillis()
      val completed =
          saveCompletions.poll(remaining.coerceIn(0, AWAIT_POLL_INTERVAL_MS), TimeUnit.MILLISECONDS)
      if (completed != null) return true
      if (!connected || !isAlive()) return false
      if (remaining <= 0) return false
    }
  }

  /**
   * Waits for the companion's [LoadReport] answering [expectedRequestId]. Reports for other request
   * ids are discarded (stale answers from a previous reload). The queue is drained before the
   * liveness checks so a report that arrived just as the process died still wins — the load
   * genuinely completed.
   */
  fun awaitReport(
      expectedRequestId: String,
      timeoutMs: Long,
      isAlive: () -> Boolean,
  ): LoadWaitResult {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      val remaining = deadline - System.currentTimeMillis()
      val report =
          reports.poll(remaining.coerceIn(0, AWAIT_POLL_INTERVAL_MS), TimeUnit.MILLISECONDS)
      if (report != null) {
        if (report.requestId == expectedRequestId) return report.toWaitResult()
        continue // Stale requestId — drop and keep waiting.
      }
      if (!connected || !isAlive()) return LoadWaitResult.ServerExited
      if (remaining <= 0) return LoadWaitResult.TimedOut
    }
  }

  private fun LoadReport.toWaitResult(): LoadWaitResult =
      if (status == LoadStatus.OK) LoadWaitResult.Ok(this)
      else LoadWaitResult.Failed(message ?: "plugin load failed", this)

  override fun close() {
    synchronized(lock) {
      connected = false
      try {
        socket?.close()
      } catch (_: IOException) {}
      socket = null
      writer = null
    }
    readerThread?.join(READER_JOIN_TIMEOUT_MS)
    readerThread = null
  }
}

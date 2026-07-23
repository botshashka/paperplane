package dev.paperplane.cli.server

import dev.paperplane.cli.devserver.LoadRequest
import dev.paperplane.cli.devserver.LoadWaitResult
import dev.paperplane.cli.devserver.socketLoadResultWaiter
import dev.paperplane.cli.testing.FakeCompanionSocket
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [PaperServerManager]'s side of the companion socket protocol: the ready wait (dial + explicit
 * `ready` event — never connection-as-readiness), the save round-trip, best-effort sends, and
 * start()'s stale-file hygiene. Split from [PaperServerManagerTest] (config/deploy coverage), which
 * is at the LargeClass ceiling. Driven against a [FakeCompanionSocket] over real TCP.
 */
class PaperServerManagerSocketTest {

  @TempDir lateinit var tempDir: File
  private val terminal = RecordingTerminal()
  private val ui = TerminalUI(terminal)

  private fun createManager(port: Int = 25566, protocolLog: Boolean = false): PaperServerManager {
    val serverDir = File(tempDir, "server-$port")
    val cacheDir = File(tempDir, "cache")
    return PaperServerManager(
        serverDir,
        PaperDownloader(cacheDir),
        ui,
        port = port,
        protocolLog = protocolLog,
    )
  }

  // ── protocol-log wiring (dev.protocol-log → live tee) ───────────────

  @Test
  fun `protocolLog true tees the companion protocol to a log file`() {
    val manager = createManager(protocolLog = true)
    manager.serverDir.mkdirs()
    FakeCompanionSocket(manager.serverDir).start().use { companion ->
      withSleeperProcess(manager) {
        Thread {
              companion.awaitConnection()
              companion.sendReady()
            }
            .start()
        assertTrue(manager.waitForReady())
      }
    }
    val log = File(manager.serverDir, ".paperplane/protocol-log.ndjson")
    assertTrue(log.exists(), "protocolLog=true must tee the protocol to a log file")
    val text = log.readText()
    assertTrue(
        text.contains("\"dir\":\"send\"") && text.contains("hello"),
        "the teed log must include the sent handshake; got: $text",
    )
    assertTrue(text.contains("\"dir\":\"recv\""), "the teed log must include received lines")
  }

  @Test
  fun `protocolLog false writes no protocol log`() {
    val manager = createManager() // protocolLog defaults to false
    manager.serverDir.mkdirs()
    FakeCompanionSocket(manager.serverDir).start().use { companion ->
      withSleeperProcess(manager) {
        Thread {
              companion.awaitConnection()
              companion.sendReady()
            }
            .start()
        assertTrue(manager.waitForReady())
      }
    }
    assertFalse(
        File(manager.serverDir, ".paperplane/protocol-log.ndjson").exists(),
        "protocolLog=false must not create a protocol log",
    )
  }

  // ── Best-effort sends ───────────────────────────────────────────────

  @Test
  fun `sendCompanionStatus is a no-op without a companion connection`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    // Advisory sends must be droppable: reporting an error while nothing is running cannot throw.
    manager.sendCompanionStatus("error", message = "Build failed")
  }

  @Test
  fun `sendLoadRequest without a connection reports false`() {
    val manager = createManager()
    assertFalse(
        manager.sendLoadRequest(LoadRequest(requestId = "r1", jarPath = "/x", pluginName = "P"))
    )
  }

  // ── saveWorld ───────────────────────────────────────────────────────

  @Test
  fun `saveWorld returns false without a companion connection`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    assertFalse(manager.saveWorld(timeoutMs = 300))
  }

  @Test
  fun `saveWorld sends the saving status and resolves on the saveComplete event`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    FakeCompanionSocket(manager.serverDir).start().use { companion ->
      withSleeperProcess(manager) {
        // Establish the connection first: dial + explicit ready event.
        Thread {
              companion.awaitConnection()
              companion.sendReady()
            }
            .start()
        assertTrue(manager.waitForReady(), "connection + ready event must succeed")

        // Answer the incoming `saving` status with a saveComplete event.
        Thread {
              companion.awaitReceived(2) // hello + status
              companion.sendSaveComplete()
            }
            .start()

        assertTrue(manager.saveWorld(timeoutMs = 5_000))
        assertTrue(
            companion.received.any { it.contains("\"state\":\"saving\"") },
            "saveWorld must send the saving status; received: ${companion.received}",
        )
      }
    }
  }

  @Test
  fun `saveWorld times out when the companion never answers`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    FakeCompanionSocket(manager.serverDir, serverReadyOnWelcome = true).start().use { _ ->
      withSleeperProcess(manager) {
        assertTrue(manager.waitForReady(), "welcome snapshot alone must satisfy readiness")
        assertFalse(manager.saveWorld(timeoutMs = 300), "no saveComplete → timeout")
      }
    }
  }

  // ── awaitLoadReport / socketLoadResultWaiter ────────────────────────

  @Test
  fun `awaitLoadReport returns ServerExited without a companion connection`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    // No connection at all (the server was never started) counts as exited — no report can arrive.
    assertEquals(LoadWaitResult.ServerExited, manager.awaitLoadReport("r1", 300))
  }

  @Test
  fun `awaitLoadReport resolves the companion's matching report`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    FakeCompanionSocket(manager.serverDir).start().use { companion ->
      withSleeperProcess(manager) {
        Thread {
              companion.awaitConnection()
              companion.sendReady()
            }
            .start()
        assertTrue(manager.waitForReady())

        companion.send("""{"type":"report","requestId":"r1","status":"ok"}""")
        assertInstanceOf(LoadWaitResult.Ok::class.java, manager.awaitLoadReport("r1", 5_000))
      }
    }
  }

  @Test
  fun `socketLoadResultWaiter delegates to the manager's awaitLoadReport`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    // The production waiter is a thin delegate: with no connection it yields ServerExited too.
    assertEquals(LoadWaitResult.ServerExited, socketLoadResultWaiter().await(manager, "r1", 300))
  }

  // ── waitForReady ────────────────────────────────────────────────────
  // waitForReady = dial the companion socket + await the explicit `ready` event. The two are
  // deliberately separate signals: a completed connection only proves the companion enabled.

  @Test
  fun `waitForReady returns false when not started`() {
    val manager = createManager()
    assertFalse(manager.waitForReady())
  }

  @Test
  fun `waitForReady connects to the companion socket and awaits the streamed ready event`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    FakeCompanionSocket(manager.serverDir).start().use { companion ->
      withSleeperProcess(manager) {
        Thread {
              companion.awaitConnection()
              Thread.sleep(100)
              companion.sendReady()
            }
            .start()

        assertTrue(manager.waitForReady())
      }
    }
  }

  @Test
  fun `waitForReady is satisfied by the welcome snapshot when the server was already ready`() {
    // The reconnect case: ServerLoadEvent fired before the CLI connected, so no ready event will
    // ever stream — the welcome's serverReady snapshot must carry the signal.
    val manager = createManager()
    manager.serverDir.mkdirs()
    FakeCompanionSocket(manager.serverDir, serverReadyOnWelcome = true).start().use { _ ->
      withSleeperProcess(manager) { assertTrue(manager.waitForReady()) }
    }
  }

  @Test
  fun `an established connection alone does not satisfy waitForReady`() {
    // Gate-1 finding: connect-level probes false-pass. Readiness must be the explicit event.
    val manager = createManager()
    manager.serverDir.mkdirs()
    FakeCompanionSocket(manager.serverDir).start().use { companion ->
      withSleeperProcess(manager) {
        val connected = CountDownLatch(1)
        Thread {
              companion.awaitConnection()
              connected.countDown()
            }
            .start()

        val result = AtomicReference<Boolean>()
        val waiter = Thread { result.set(manager.waitForReady()) }
        waiter.start()

        assertTrue(connected.await(5, TimeUnit.SECONDS))
        // Give the waiter a moment: it must still be blocked despite the live connection.
        waiter.join(500)
        assertTrue(waiter.isAlive, "a connection without a ready event must not unblock the wait")

        companion.sendReady()
        waiter.join(5_000)
        assertEquals(true, result.get())
      }
    }
  }

  @Test
  fun `waitForReady surfaces companion-error during the dial and returns false`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    File(manager.serverDir, ".paperplane").mkdirs()

    // No companion socket ever comes up — the bootstrap error file is the only signal.
    withSleeperProcess(manager) {
      val errorFile = File(manager.serverDir, ".paperplane/companion-error")
      Thread {
            Thread.sleep(100)
            errorFile.parentFile.mkdirs()
            errorFile.writeText("Unsupported Paper version (Paper 1.18)\n")
          }
          .start()

      val result = manager.waitForReady()

      assertFalse(result, "companion-error must make waitForReady fail")
      assertFalse(errorFile.exists(), "companion-error flag must be consumed (deleted)")
      assertTrue(
          terminal.raw.contains("Unsupported Paper version (Paper 1.18)"),
          "the companion's error message must be surfaced to the user, got: ${terminal.raw}",
      )
    }
  }

  @Test
  fun `waitForReady shows a fallback message when companion-error is empty`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    File(manager.serverDir, ".paperplane").mkdirs()

    withSleeperProcess(manager) {
      Thread {
            Thread.sleep(100)
            val errorFile = File(manager.serverDir, ".paperplane/companion-error")
            errorFile.parentFile.mkdirs()
            errorFile.writeText("   ")
          }
          .start()

      val result = manager.waitForReady()

      assertFalse(result)
      assertTrue(
          terminal.raw.contains("PaperPlane companion failed to start"),
          "an empty error flag must still surface a fallback message, got: ${terminal.raw}",
      )
    }
  }

  @Test
  fun `waitForReady returns false when the server process dies before the companion publishes`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    // A process that exits immediately: java with a nonexistent jar.
    val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
    manager.start(
        File(tempDir, "missing.jar"),
        LaunchSpec(javaBin, isJbr = false, jvmArgs = emptyList(), attachAgent = false),
    )
    val deadline = System.currentTimeMillis() + 30_000
    while (manager.isRunning()) {
      if (System.currentTimeMillis() > deadline) throw AssertionError("process never exited")
      Thread.sleep(50)
    }

    val start = System.currentTimeMillis()
    assertFalse(manager.waitForReady())
    assertTrue(
        System.currentTimeMillis() - start < 30_000,
        "a dead process must short-circuit the ready wait",
    )
  }

  // ── start() protocol hygiene ────────────────────────────────────────

  @Test
  fun `start clears a stale handshake file and companion-error before launching`() {
    val manager = createManager()
    manager.serverDir.mkdirs()
    val ppDir = File(manager.serverDir, ".paperplane").apply { mkdirs() }
    val staleSocket =
        File(ppDir, "companion-socket.json").apply {
          writeText("""{"port":1,"token":"stale","protocolVersion":3}""")
        }
    val staleError = File(ppDir, "companion-error").apply { writeText("stale") }

    val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
    manager.start(
        File(tempDir, "missing.jar"),
        LaunchSpec(javaBin, isJbr = false, jvmArgs = emptyList(), attachAgent = false),
    )
    manager.stop()

    assertFalse(staleSocket.exists(), "a stale handshake file could dial a reassigned port")
    assertFalse(staleError.exists(), "a stale companion-error would abort a healthy start")
  }

  /**
   * Runs [body] with a genuinely-alive child process installed as the manager's server process (via
   * reflection — start() would spawn a real Paper JVM). Portable: the sleeper is a Java
   * source-launcher one-liner, no POSIX `sleep` binary involved.
   */
  private fun withSleeperProcess(manager: PaperServerManager, body: () -> Unit) {
    val sleeperSrc = File(tempDir, "Sleeper.java")
    if (!sleeperSrc.exists()) {
      sleeperSrc.writeText(
          "public class Sleeper { public static void main(String[] a) throws Exception { Thread.sleep(120_000); } }"
      )
    }
    val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
    val proc = ProcessBuilder(javaBin, sleeperSrc.absolutePath).start()
    val processField = PaperServerManager::class.java.getDeclaredField("process")
    processField.isAccessible = true
    processField.set(manager, proc)
    try {
      body()
    } finally {
      proc.destroyForcibly()
    }
  }
}

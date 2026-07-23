package dev.paperplane.cli.ipc

import dev.paperplane.cli.devserver.InstantWaitResult
import dev.paperplane.cli.devserver.LoadRequest
import dev.paperplane.cli.devserver.LoadStatus
import dev.paperplane.cli.devserver.LoadWaitResult
import dev.paperplane.cli.testing.FakeCompanionSocket
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Exercises [CompanionClient] against a [FakeCompanionSocket] over real localhost TCP: the dial
 * loop (discovery, stale ports, auth rejection, version mismatch), the readiness contract (explicit
 * event or welcome snapshot — never the connection itself), report matching, save completion,
 * disconnect-as-crash, and the debug tee.
 */
class CompanionClientTest {

  @TempDir lateinit var serverDir: File

  private val alive = { true }

  private fun connectOrFail(client: CompanionClient, timeoutMs: Long = 5_000) {
    assertEquals(CompanionClient.ConnectOutcome.Connected, client.connect(timeoutMs, alive))
  }

  // ── Dial loop ───────────────────────────────────────────────────────

  @Test
  fun `connects once the companion publishes its handshake file`() {
    FakeCompanionSocket(serverDir).use { companion ->
      CompanionClient(serverDir).use { client ->
        // Publish AFTER the dial loop starts, like a booting server would.
        Thread {
              Thread.sleep(200)
              companion.start()
            }
            .start()

        connectOrFail(client)
        assertTrue(client.isConnected)
        companion.awaitConnection()
        assertTrue(
            companion.received.first().contains("\"type\":\"hello\""),
            "the first message must be the authenticated hello",
        )
      }
    }
  }

  @Test
  fun `times out when no handshake file ever appears`() {
    CompanionClient(serverDir).use { client ->
      assertEquals(CompanionClient.ConnectOutcome.TimedOut, client.connect(300, alive))
    }
  }

  @Test
  fun `reports Died when the server process dies while dialing`() {
    CompanionClient(serverDir).use { client ->
      val start = System.currentTimeMillis()
      assertEquals(CompanionClient.ConnectOutcome.Died, client.connect(10_000, isAlive = { false }))
      assertTrue(System.currentTimeMillis() - start < 5_000, "death must short-circuit the dial")
    }
  }

  @Test
  fun `surfaces the companion bootstrap error while dialing`() {
    CompanionClient(serverDir).use { client ->
      val outcome = client.connect(10_000, alive) { "Unsupported Paper version" }
      assertEquals(
          CompanionClient.ConnectOutcome.CompanionFailed("Unsupported Paper version"),
          outcome,
      )
    }
  }

  @Test
  fun `keeps dialing past a stale handshake file pointing at a dead port`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      // Overwrite discovery with a port nothing listens on; repair it shortly after — the dial
      // loop must retry through the refused connections and then succeed.
      companion.writeHandshakeFile(portOverride = findDeadPort())
      Thread {
            Thread.sleep(300)
            companion.writeHandshakeFile()
          }
          .start()

      CompanionClient(serverDir).use { client -> connectOrFail(client) }
    }
  }

  @Test
  fun `a companion that accepts but never answers the hello does not wedge the dial`() {
    // A squatted port can complete the TCP handshake and then say nothing; the handshake read
    // timeout must bound the attempt so the dial loop stays live.
    FakeCompanionSocket(serverDir, answerHello = false).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        assertEquals(CompanionClient.ConnectOutcome.TimedOut, client.connect(500, alive))
        assertFalse(client.isConnected)
      }
    }
  }

  @Test
  fun `a welcome with the wrong protocol version fails fast with a rebuild hint`() {
    FakeCompanionSocket(serverDir, welcomeProtocolVersion = 99).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        // A version-skewed companion won't heal by re-dialing, so connect() surfaces it immediately
        // as CompanionFailed rather than spinning the same mismatch until the timeout.
        val start = System.currentTimeMillis()
        val outcome = client.connect(10_000, alive)
        val failed =
            assertInstanceOf(CompanionClient.ConnectOutcome.CompanionFailed::class.java, outcome)
        assertTrue(
            failed.message.contains("rebuild"),
            "the message should tell the user to rebuild",
        )
        assertTrue(
            System.currentTimeMillis() - start < 5_000,
            "a version mismatch must not wait out the connect timeout",
        )
        assertFalse(client.isConnected)
      }
    }
  }

  @Test
  fun `a token-rejecting companion closes the socket and the dial keeps retrying`() {
    // The fake drops non-matching tokens exactly like the real companion; feed the client a
    // handshake file whose token the companion won't accept.
    FakeCompanionSocket(serverDir, token = "real-token").use { companion ->
      companion.start()
      val file = CompanionSocketFile.path(serverDir)
      file.writeText(file.readText().replace("real-token", "wrong-token"))

      CompanionClient(serverDir).use { client ->
        assertEquals(CompanionClient.ConnectOutcome.TimedOut, client.connect(500, alive))
      }
    }
  }

  // ── Readiness ───────────────────────────────────────────────────────

  @Test
  fun `an established connection does not imply readiness`() {
    // Gate-1 finding: connect-level probes false-pass. Readiness is only ever the explicit
    // streamed event (or its welcome snapshot) — never a connection side-effect.
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        assertFalse(client.serverReady)
        assertFalse(client.awaitServerReady(200, alive), "no event → not ready, however connected")
      }
    }
  }

  @Test
  fun `the streamed ready event resolves the readiness wait`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        Thread {
              companion.awaitConnection()
              Thread.sleep(100)
              companion.sendReady()
            }
            .start()

        assertTrue(client.awaitServerReady(5_000, alive))
        assertTrue(client.serverReady)
      }
    }
  }

  @Test
  fun `the welcome snapshot alone satisfies readiness on reconnect`() {
    FakeCompanionSocket(serverDir, serverReadyOnWelcome = true).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        assertTrue(client.serverReady, "welcome serverReady=true must latch immediately")
        assertTrue(client.awaitServerReady(1_000, alive))
      }
    }
  }

  @Test
  fun `readiness wait aborts when the connection drops`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        companion.awaitConnection()
        Thread {
              Thread.sleep(100)
              companion.dropConnection()
            }
            .start()

        val start = System.currentTimeMillis()
        assertFalse(client.awaitServerReady(10_000, alive))
        assertTrue(
            System.currentTimeMillis() - start < 5_000,
            "a dropped connection is the crash signal and must short-circuit the wait",
        )
      }
    }
  }

  @Test
  fun `readiness wait aborts when the process dies`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        assertFalse(client.awaitServerReady(10_000) { false })
      }
    }
  }

  // ── Sends ───────────────────────────────────────────────────────────

  @Test
  fun `sends status and load messages the companion receives in order`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        companion.awaitConnection()

        assertTrue(client.sendStatus("building"))
        assertTrue(client.sendLoadRequest(LoadRequest("r1", "/x.jar", "Sample")))

        companion.awaitReceived(3) // hello + status + load
        assertTrue(companion.received[1].contains("\"state\":\"building\""))
        assertTrue(companion.received[2].contains("\"type\":\"load\""))
        assertTrue(companion.received[2].contains("\"requestId\":\"r1\""))
      }
    }
  }

  @Test
  fun `sends before a connection report false`() {
    CompanionClient(serverDir).use { client ->
      assertFalse(client.sendStatus("building"))
      assertFalse(client.sendLoadRequest(LoadRequest("r1", "/x.jar", "Sample")))
    }
  }

  // ── Reports ─────────────────────────────────────────────────────────

  @Test
  fun `awaitReport resolves Ok for a matching ok report`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        companion.awaitConnection()
        companion.send(
            """{"type":"report","requestId":"r1","status":"ok","strategy":"fresh","pluginName":"P"}"""
        )

        val result = client.awaitReport("r1", 5_000, alive)

        val ok = assertInstanceOf(LoadWaitResult.Ok::class.java, result)
        assertEquals("r1", ok.report!!.requestId)
        assertEquals(LoadStatus.OK, ok.report!!.status)
      }
    }
  }

  @Test
  fun `awaitReport resolves Failed with the message for a failed report`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        companion.awaitConnection()
        companion.send(
            """{"type":"report","requestId":"r1","status":"failed","message":"plugin.yml not found"}"""
        )

        val result = client.awaitReport("r1", 5_000, alive)

        val failed = assertInstanceOf(LoadWaitResult.Failed::class.java, result)
        assertEquals("plugin.yml not found", failed.message)
        assertEquals("r1", failed.report!!.requestId)
      }
    }
  }

  @Test
  fun `a report with an unrecognized status resolves Failed, not Ok`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        companion.awaitConnection()
        companion.send("""{"type":"report","requestId":"r1","status":"exploded"}""")

        assertInstanceOf(
            LoadWaitResult.Failed::class.java,
            client.awaitReport("r1", 5_000, alive),
            "an unknown terminal status must never be read as success",
        )
      }
    }
  }

  @Test
  fun `a stale report from a previous request is discarded`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        companion.awaitConnection()
        companion.send("""{"type":"report","requestId":"OLD","status":"ok"}""")
        Thread {
              Thread.sleep(200)
              companion.send("""{"type":"report","requestId":"NEW","status":"ok"}""")
            }
            .start()

        val result = client.awaitReport("NEW", 5_000, alive)

        val ok = assertInstanceOf(LoadWaitResult.Ok::class.java, result)
        assertEquals("NEW", ok.report!!.requestId, "the stale report must be dropped, not returned")
      }
    }
  }

  @Test
  fun `awaitReport times out when no report arrives`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        assertEquals(LoadWaitResult.TimedOut, client.awaitReport("r1", 300, alive))
      }
    }
  }

  @Test
  fun `awaitReport resolves ServerExited when the connection drops`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        companion.awaitConnection()
        Thread {
              Thread.sleep(100)
              companion.dropConnection()
            }
            .start()

        val start = System.currentTimeMillis()
        assertEquals(LoadWaitResult.ServerExited, client.awaitReport("r1", 10_000, alive))
        assertTrue(System.currentTimeMillis() - start < 5_000)
      }
    }
  }

  @Test
  fun `awaitReport resolves ServerExited when the process dies`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        assertEquals(LoadWaitResult.ServerExited, client.awaitReport("r1", 10_000) { false })
      }
    }
  }

  // ── Instant reports ─────────────────────────────────────────────────
  // Same await contract as load reports (one shared loop); these pin the instant mapper: the
  // typed Answered result, the stale-id drop, and the dead-process short-circuit.

  @Test
  fun `awaitInstantReport resolves Answered for the matching report and drops stale ids`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        companion.awaitConnection()
        companion.send("""{"type":"instantReport","requestId":"STALE","status":"ok","patched":9}""")
        companion.send(
            """{"type":"instantReport","requestId":"i1","status":"ok","patched":1,""" +
                """"appliedClasses":["com.example.Foo"]}"""
        )

        val result = client.awaitInstantReport("i1", 5_000, alive)

        val answered = assertInstanceOf(InstantWaitResult.Answered::class.java, result)
        assertEquals("i1", answered.report.requestId)
        assertEquals(1, answered.report.patched)
        assertEquals(listOf("com.example.Foo"), answered.report.appliedClasses)
      }
    }
  }

  @Test
  fun `awaitInstantReport resolves ServerExited when the process dies`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        assertEquals(
            InstantWaitResult.ServerExited,
            client.awaitInstantReport("i1", 10_000) { false },
        )
      }
    }
  }

  @Test
  fun `a report that arrived before the drop still wins`() {
    // Durable-result-first: the host answered and THEN the process died within the same window —
    // the reload genuinely completed, so the queued report must win over ServerExited.
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        companion.awaitConnection()
        companion.send("""{"type":"report","requestId":"r1","status":"ok"}""")
        // Give the reader thread a moment to queue the report before the drop.
        Thread.sleep(200)
        companion.dropConnection()

        assertInstanceOf(LoadWaitResult.Ok::class.java, client.awaitReport("r1", 5_000, alive))
      }
    }
  }

  // ── Save completion ─────────────────────────────────────────────────

  @Test
  fun `awaitSaveComplete resolves on the event and times out without one`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        companion.awaitConnection()

        companion.sendSaveComplete()
        assertTrue(client.awaitSaveComplete(5_000, alive))
        assertFalse(client.awaitSaveComplete(200, alive), "the event must be consumed exactly once")
      }
    }
  }

  @Test
  fun `awaitSaveComplete short-circuits when the connection drops`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        companion.awaitConnection()
        companion.dropConnection()

        val start = System.currentTimeMillis()
        assertFalse(client.awaitSaveComplete(10_000, alive))
        assertTrue(
            System.currentTimeMillis() - start < 5_000,
            "a dropped connection must short-circuit the save wait, not block the full timeout",
        )
      }
    }
  }

  @Test
  fun `drainSaveCompletions drops a stale completion`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        companion.awaitConnection()
        companion.sendSaveComplete()
        Thread.sleep(200) // Let the reader queue it.

        client.drainSaveCompletions()

        assertFalse(
            client.awaitSaveComplete(200, alive),
            "a stale completion must not satisfy a new save",
        )
      }
    }
  }

  // ── Progress events ─────────────────────────────────────────────────

  @Test
  fun `streamed load progress is ignored without dropping the session`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        companion.awaitConnection()
        // No consumer reads loadProgress yet (Fresh/Instant mode will); the line must be skipped
        // without wedging the reader, so a following report still arrives.
        companion.send("""{"type":"loadProgress","requestId":"r1","stage":"loading"}""")
        companion.send("""{"type":"report","requestId":"r1","status":"ok"}""")

        assertInstanceOf(LoadWaitResult.Ok::class.java, client.awaitReport("r1", 5_000, alive))
        assertTrue(client.isConnected)
      }
    }
  }

  @Test
  fun `garbage lines from the companion are skipped without dropping the session`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      CompanionClient(serverDir).use { client ->
        connectOrFail(client)
        companion.awaitConnection()
        companion.send("not json at all")
        companion.send("""{"type":"mystery"}""")
        companion.send("""{"type":"report","requestId":"r1","status":"ok"}""")

        assertInstanceOf(LoadWaitResult.Ok::class.java, client.awaitReport("r1", 5_000, alive))
        assertTrue(client.isConnected)
      }
    }
  }

  // ── Tee ─────────────────────────────────────────────────────────────

  @Test
  fun `the tee records both directions including the handshake`() {
    FakeCompanionSocket(serverDir).use { companion ->
      companion.start()
      val tee = ProtocolTee.forServer(serverDir)
      CompanionClient(serverDir, tee).use { client ->
        connectOrFail(client)
        companion.awaitConnection()
        client.sendStatus("building")
        companion.sendReady()
        val deadline = System.currentTimeMillis() + 5_000
        while (!client.serverReady && System.currentTimeMillis() < deadline) Thread.sleep(10)
      }

      val log = File(serverDir, ".paperplane/protocol-log.ndjson").readText()
      assertTrue(log.contains("\"dir\":\"send\""))
      assertTrue(log.contains("\"dir\":\"recv\""))
      assertTrue(log.contains("hello"), "the handshake must be teed")
      assertTrue(log.contains("welcome"))
      assertTrue(log.contains("building"))
      assertTrue(log.contains("ready"))
    }
  }

  /** Finds a port with nothing listening by binding-and-releasing an ephemeral one. */
  private fun findDeadPort(): Int = java.net.ServerSocket(0).use { it.localPort }
}

package dev.paperplane.companion

import dev.paperplane.companion.CompanionSocketServer.StatusUpdate
import dev.paperplane.companion.host.HostLoadReport
import dev.paperplane.companion.host.HostLoadRequest
import dev.paperplane.companion.host.HostLoadResult
import dev.paperplane.companion.host.HostLoadStatus
import dev.paperplane.companion.host.HostReloadStrategy
import dev.paperplane.companion.host.InnerPluginHost
import dev.paperplane.companion.host.LeakAttribution
import dev.paperplane.companion.host.LeakDiagnosticsMode
import dev.paperplane.companion.host.LeakSummary
import dev.paperplane.companion.host.ReflectionProbe
import dev.paperplane.companion.host.UnsupportedPaperVersionException
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Server
import org.bukkit.command.SimpleCommandMap
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.SimplePluginManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/**
 * Drives [CompanionMessageHandler.handleLoadRequest] / [CompanionMessageHandler.handleStatus]
 * directly (the socket server hops messages onto the main thread before they reach the handler, so
 * tests call the handler the same way) with a recording fake host and a [RecordingIpc]. Verifies
 * the contract between the CLI's socket messages and the host:
 *
 * - Each load request triggers a dispatch, streams a `loading` progress stage, and is answered with
 *   a report echoing its requestId.
 * - Failed dispatches and host-init failures answer with failed reports, never silence.
 * - Status updates drive broadcasts, the save-protection window, and the world save.
 */
class CompanionMessageHandlerTest {

  @TempDir lateinit var tempDir: File

  private lateinit var server: ServerMock
  private lateinit var hostingPlugin: org.bukkit.plugin.java.JavaPlugin
  private lateinit var fakeHost: RecordingHost
  private lateinit var ipc: RecordingIpc
  private lateinit var handler: CompanionMessageHandler
  private lateinit var serverRoot: File
  private lateinit var fakeServer: FakeServerWithSpm
  private lateinit var probe: ReflectionProbe

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
    hostingPlugin = MockBukkit.createMockPlugin("PaperPlane")

    serverRoot = File(tempDir, "server").apply { mkdirs() }

    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    fakeServer = FakeServerWithSpm(server, spm)
    probe = ReflectionProbe.probe(fakeServer)
    fakeHost = RecordingHost(fakeServer, javaClass.classLoader, probe)
    ipc = RecordingIpc()

    handler = CompanionMessageHandler(hostingPlugin, { fakeHost }, ipc, serverRoot)
  }

  @AfterEach
  fun tearDown() {
    MockBukkit.unmock()
  }

  private fun request(id: String) =
      HostLoadRequest(requestId = id, jarPath = "/x.jar", pluginName = "Sample")

  // ── Dispatch + report shape ─────────────────────────────────────────

  @Test
  fun `a load request dispatches to the host and answers with an ok report`() {
    handler.handleLoadRequest(request("r1"))

    assertEquals(1, fakeHost.dispatches)
    assertEquals("r1", fakeHost.lastRequest!!.requestId)
    val report = ipc.reports.single()
    assertEquals("r1", report.requestId, "report must echo the request id for CLI matching")
    assertEquals(HostLoadStatus.OK, report.status)
    // Fake host reports isLoaded()==false, so a first load is the "fresh" strategy.
    assertEquals(HostReloadStrategy.FRESH, report.strategy)
    assertEquals("Sample", report.pluginName)
  }

  @Test
  fun `a load request streams the loading progress stage before the report`() {
    handler.handleLoadRequest(request("r1"))

    assertEquals(listOf("r1" to "loading"), ipc.progress)
    assertEquals(
        listOf("progress", "report"),
        ipc.order.filter { it == "progress" || it == "report" },
        "the loading stage must be streamed before the terminal report",
    )
  }

  @Test
  fun `reload of an already-loaded plugin reports the reload strategy`() {
    fakeHost.isLoadedResult = true
    handler.handleLoadRequest(request("r1"))

    assertEquals(HostReloadStrategy.RELOAD, ipc.reports.single().strategy)
  }

  @Test
  fun `leaks and action from the host result propagate into the report`() {
    fakeHost.nextResult =
        HostLoadResult.Ok(
            "Sample",
            5,
            leaks =
                LeakSummary(
                    consecutive = 2,
                    confirmedSurvivors = 3,
                    attribution = listOf(LeakAttribution("thread", "timer still running")),
                ),
            action = "restart",
        )
    handler.handleLoadRequest(request("r1"))

    val report = ipc.reports.single()
    assertEquals(2, report.leaks!!.consecutive)
    assertEquals(3, report.leaks!!.confirmedSurvivors)
    assertEquals("timer still running", report.leaks!!.attribution.single().detail)
    assertEquals("restart", report.action)
  }

  @Test
  fun `failed dispatch answers with a failed report carrying the message`() {
    fakeHost.nextResult = HostLoadResult.Failed("plugin.yml not found", 12)
    handler.handleLoadRequest(request("r1"))

    val report = ipc.reports.single()
    assertEquals("r1", report.requestId)
    assertEquals(HostLoadStatus.FAILED, report.status)
    assertEquals("plugin.yml not found", report.message)
  }

  // ── deferred leak dumps (full mode) ─────────────────────────────────

  /**
   * A leaking Ok result. `maybeDeferLeakDump` keys off [HostLoadResult.Ok.leaks] being non-null, so
   * this is the shape the host produces when a reload confirms a surviving classloader.
   */
  private fun okWithLeak() =
      HostLoadResult.Ok(
          "Sample",
          5,
          leaks =
              LeakSummary(
                  consecutive = 1,
                  confirmedSurvivors = 1,
                  attribution = listOf(LeakAttribution("thread", "MyPlugin-Timer still running")),
              ),
      )

  /** RecordingHost whose dump methods record the report-send state at the moment they run. */
  private fun dumpRecordingHost(mode: LeakDiagnosticsMode, log: MutableList<String>) =
      object : RecordingHost(fakeServer, javaClass.classLoader, probe, mode) {
        override fun dumpVerboseDiagnostics() {
          log += if (ipc.reports.isNotEmpty()) "verbose:present" else "verbose:missing"
        }

        override fun tryDumpHeap(target: File) {
          log += if (ipc.reports.isNotEmpty()) "heap:present" else "heap:missing"
          // The heap dump must target the server root, never user.dir.
          log += "heap-target:${target.path}"
        }
      }

  @Test
  fun `full mode defers verbose and heap dumps until after the report is sent`() {
    val log = CopyOnWriteArrayList<String>()
    val host = dumpRecordingHost(LeakDiagnosticsMode.FULL, log).apply { nextResult = okWithLeak() }
    val deferHandler = CompanionMessageHandler(hostingPlugin, { host }, ipc, serverRoot)

    deferHandler.handleLoadRequest(request("r1"))

    // The report is sent synchronously; the verbose (sync) dump is queued for a later tick, so it
    // must not have run yet. This is the deferral guarantee: the CLI's waiter is unblocked first.
    assertTrue(ipc.reports.isNotEmpty(), "report must be sent synchronously")
    assertFalse(log.any { it.startsWith("verbose") }, "verbose dump must be deferred, not inline")

    server.scheduler.performTicks(1)
    server.scheduler.waitAsyncTasksFinished()

    assertTrue(
        log.contains("verbose:present"),
        "verbose dump ran and saw the report already sent",
    )
    assertTrue(log.contains("heap:present"), "heap dump ran and saw the report already sent")
    assertFalse(log.any { it.endsWith(":missing") }, "no dump may run before the report is sent")
  }

  @Test
  fun `full mode heap dump targets the server root not user dir`() {
    val log = CopyOnWriteArrayList<String>()
    val host = dumpRecordingHost(LeakDiagnosticsMode.FULL, log).apply { nextResult = okWithLeak() }
    val deferHandler = CompanionMessageHandler(hostingPlugin, { host }, ipc, serverRoot)

    deferHandler.handleLoadRequest(request("r1"))
    server.scheduler.performTicks(1)
    server.scheduler.waitAsyncTasksFinished()

    val expected = File(serverRoot, ".paperplane/leak.hprof").path
    assertTrue(
        log.contains("heap-target:$expected"),
        "heap dump must target .paperplane/leak.hprof under the server root; log=$log",
    )
  }

  @Test
  fun `summary mode schedules no dumps even when a leak is confirmed`() {
    val log = CopyOnWriteArrayList<String>()
    val host =
        dumpRecordingHost(LeakDiagnosticsMode.SUMMARY, log).apply { nextResult = okWithLeak() }
    val h = CompanionMessageHandler(hostingPlugin, { host }, ipc, serverRoot)

    h.handleLoadRequest(request("r1"))
    server.scheduler.performTicks(1)
    server.scheduler.waitAsyncTasksFinished()

    assertTrue(log.isEmpty(), "summary mode logs + reports attribution but never dumps; log=$log")
  }

  @Test
  fun `off mode schedules no dumps even when a leak is confirmed`() {
    val log = CopyOnWriteArrayList<String>()
    val host = dumpRecordingHost(LeakDiagnosticsMode.OFF, log).apply { nextResult = okWithLeak() }
    val h = CompanionMessageHandler(hostingPlugin, { host }, ipc, serverRoot)

    h.handleLoadRequest(request("r1"))
    server.scheduler.performTicks(1)
    server.scheduler.waitAsyncTasksFinished()

    assertTrue(log.isEmpty(), "off disables dump output; log=$log")
  }

  @Test
  fun `full mode without a confirmed leak schedules no dumps`() {
    val log = CopyOnWriteArrayList<String>()
    // A clean reload: Ok with leaks == null. Nothing to dump.
    val host =
        dumpRecordingHost(LeakDiagnosticsMode.FULL, log).apply {
          nextResult = HostLoadResult.Ok("Sample", 5)
        }
    val h = CompanionMessageHandler(hostingPlugin, { host }, ipc, serverRoot)

    h.handleLoadRequest(request("r1"))
    server.scheduler.performTicks(1)
    server.scheduler.waitAsyncTasksFinished()

    assertTrue(log.isEmpty(), "no leak → no dump, even in full mode; log=$log")
  }

  // ── Restart-action broadcasts ───────────────────────────────────────

  @Test
  fun `a restart-action failure broadcasts the restart notice`() {
    val player = server.addPlayer()
    fakeHost.nextResult =
        HostLoadResult.Failed(
            "Hot-reload paused: accumulated classloader leaks — restarting server clears them",
            0,
            action = HostLoadReport.ACTION_RESTART,
        )
    handler.handleLoadRequest(request("r1"))

    val messages = drainMessages(player)
    assertTrue(
        messages.any { it.contains("Restarting dev server to clear leaked memory") },
        "the leak restart must be announced in-game; saw: $messages",
    )
  }

  @Test
  fun `a restart-action Ok broadcasts the restart notice`() {
    // The tripping Ok is the PRIMARY restart trigger: the reload succeeded but leaks piled up past
    // the limit, so the host attaches action=restart to the Ok. Players must be warned before the
    // CLI pulls the server down, exactly as on the belt-and-braces refusal Failed.
    val player = server.addPlayer()
    // The leak limit trips on a reload of an already-loaded plugin, so the Ok reports "reloaded!".
    fakeHost.isLoadedResult = true
    fakeHost.nextResult = HostLoadResult.Ok("Sample", 5, action = HostLoadReport.ACTION_RESTART)
    handler.handleLoadRequest(request("r1"))

    val messages = drainMessages(player)
    assertTrue(
        messages.any { it.contains("Sample reloaded!") },
        "the successful reload is still announced; saw: $messages",
    )
    assertTrue(
        messages.any { it.contains("Restarting dev server to clear leaked memory") },
        "the tripping Ok must warn players before the CLI restarts; saw: $messages",
    )
  }

  @Test
  fun `an ordinary Ok broadcasts no restart notice`() {
    val player = server.addPlayer()
    fakeHost.nextResult = HostLoadResult.Ok("Sample", 5)
    handler.handleLoadRequest(request("r1"))

    val messages = drainMessages(player)
    assertFalse(
        messages.any { it.contains("Restarting dev server") },
        "an Ok with no restart action must not announce a restart; saw: $messages",
    )
  }

  @Test
  fun `an ordinary failure broadcasts no restart notice`() {
    val player = server.addPlayer()
    fakeHost.nextResult = HostLoadResult.Failed("onEnable threw", 0)
    handler.handleLoadRequest(request("r1"))

    val messages = drainMessages(player)
    assertTrue(messages.any { it.contains("Reload failed: onEnable threw") })
    assertFalse(
        messages.any { it.contains("Restarting dev server") },
        "a non-restart failure must not announce a restart; saw: $messages",
    )
  }

  // ── Lazy host provider ──────────────────────────────────────────────

  @Test
  fun `host provider is not invoked until the first load request arrives`() {
    var built = 0
    val lazyHandler =
        CompanionMessageHandler(
            hostingPlugin,
            {
              built++
              fakeHost
            },
            ipc,
            serverRoot,
        )

    // No request yet — native modes never send one, so the host must not be built.
    assertEquals(0, built, "host must not be built without a load request")
    assertNull(lazyHandler.hostOrNull)

    lazyHandler.handleLoadRequest(request("r1"))
    assertEquals(1, built, "first load request builds the host")
    assertEquals(fakeHost, lazyHandler.hostOrNull)
  }

  @Test
  fun `host provider is built once and memoized across requests`() {
    var built = 0
    val lazyHandler =
        CompanionMessageHandler(
            hostingPlugin,
            {
              built++
              fakeHost
            },
            ipc,
            serverRoot,
        )

    lazyHandler.handleLoadRequest(request("r1"))
    lazyHandler.handleLoadRequest(request("r2"))

    assertEquals(1, built, "host must be built only once, then reused")
    assertEquals(2, fakeHost.dispatches)
  }

  @Test
  fun `the first request's leak-diagnostics wire value reaches the host provider`() {
    var seenMode: LeakDiagnosticsMode? = null
    val capturingHandler =
        CompanionMessageHandler(
            hostingPlugin,
            { mode ->
              seenMode = mode
              fakeHost
            },
            ipc,
            serverRoot,
        )

    capturingHandler.handleLoadRequest(request("r1").copy(leakDiagnostics = "full"))

    assertEquals(LeakDiagnosticsMode.FULL, seenMode)
  }

  @Test
  fun `probe failure answers with a failed report and keeps the companion running`() {
    var attempts = 0
    val failingHandler =
        CompanionMessageHandler(
            hostingPlugin,
            {
              attempts++
              throw UnsupportedPaperVersionException("Paper too old for hot-reload")
            },
            ipc,
            serverRoot,
        )

    failingHandler.handleLoadRequest(request("r1"))

    assertNull(failingHandler.hostOrNull, "no host is created on probe failure")
    val report = ipc.reports.single()
    assertEquals("r1", report.requestId)
    assertEquals(HostLoadStatus.FAILED, report.status)
    assertEquals("Paper too old for hot-reload", report.message)
    assertEquals(1, attempts)
  }

  @Test
  fun `every request after a failed init is answered echoing its own requestId`() {
    var attempts = 0
    val failingHandler =
        CompanionMessageHandler(
            hostingPlugin,
            {
              attempts++
              throw UnsupportedPaperVersionException("Paper too old for hot-reload")
            },
            ipc,
            serverRoot,
        )

    failingHandler.handleLoadRequest(request("r1"))
    // The CLI's waiter filters results by requestId — a later request left unanswered (or answered
    // with a stale id) would surface as a timeout instead of the real failure.
    failingHandler.handleLoadRequest(request("r2"))

    assertEquals(1, attempts, "the init must be attempted only once, not on every request")
    assertEquals(2, ipc.reports.size)
    assertEquals(
        "r2",
        ipc.reports.last().requestId,
        "the answer must echo the CURRENT request's id",
    )
    assertEquals("Paper too old for hot-reload", ipc.reports.last().message)
  }

  @Test
  fun `unexpected init exception is reported as a failed report, not swallowed`() {
    val failingHandler =
        CompanionMessageHandler(
            hostingPlugin,
            { throw IllegalStateException("helpMap resolution blew up") },
            ipc,
            serverRoot,
        )

    // Must not throw into the dispatch.
    failingHandler.handleLoadRequest(request("r1"))

    val report = ipc.reports.single()
    assertEquals("r1", report.requestId)
    assertEquals(HostLoadStatus.FAILED, report.status)
    assertTrue(report.message!!.contains("helpMap resolution blew up"))
  }

  // ── Hot-swap fast-path eligibility ──────────────────────────────────
  // tryHotSwap needs live instrumentation that isn't available in tests, so drive the pure
  // eligibility predicate directly.

  @Test
  fun `hot-swap is eligible for a method-body edit on a loaded plugin below the leak limit`() {
    assertTrue(
        handler.hotSwapEligible(
            wasLoaded = true,
            leakLimitReached = false,
            changedClasses = listOf("com.example.Plugin"),
            classesDirs = listOf("/build/classes"),
        )
    )
  }

  @Test
  fun `hot-swap is ineligible once the leak limit is reached`() {
    // The regression: a method-body edit while leakLimitReached would take the fast path
    // and report Ok with no action, deferring the leak-restart forever. It must fall
    // through to the full reload so the host's refusal carries action=restart.
    assertFalse(
        handler.hotSwapEligible(
            wasLoaded = true,
            leakLimitReached = true,
            changedClasses = listOf("com.example.Plugin"),
            classesDirs = listOf("/build/classes"),
        )
    )
  }

  @Test
  fun `hot-swap is ineligible on a fresh load, a structural change, or a jar-only reload`() {
    assertFalse(
        handler.hotSwapEligible(
            false,
            false,
            listOf("com.example.Plugin"),
            listOf("/build/classes"),
        ),
        "not yet loaded → fresh load, not a swap",
    )
    assertFalse(
        handler.hotSwapEligible(true, false, emptyList(), listOf("/build/classes")),
        "no changed classes → structural change, needs a full reload",
    )
    assertFalse(
        handler.hotSwapEligible(true, false, listOf("com.example.Plugin"), emptyList()),
        "no classes dir → jar fallback, nothing to redefine from",
    )
  }

  // ── Status updates ──────────────────────────────────────────────────

  @Test
  fun `building blocks world edits and ready unblocks them`() {
    handler.handleStatus(StatusUpdate("building", null, null))
    assertTrue(handler.blockWorldEdits, "building must block world edits")

    handler.handleStatus(StatusUpdate("ready", "1.2s", null))
    assertFalse(handler.blockWorldEdits, "ready must unblock world edits")
  }

  @Test
  fun `saving blocks world edits, performs the save, and answers with saveComplete`() {
    handler.handleStatus(StatusUpdate("saving", null, null))

    assertTrue(handler.blockWorldEdits, "saving must block world edits")
    assertEquals(1, ipc.saveCompletions, "the save must be answered with a saveComplete event")
  }

  @Test
  fun `status transitions broadcast to online players`() {
    val player = server.addPlayer()

    handler.handleStatus(StatusUpdate("building", null, null))
    handler.handleStatus(StatusUpdate("ready", "1.2s", null))
    handler.handleStatus(StatusUpdate("error", null, "Build failed"))

    val messages = drainMessages(player)
    assertTrue(messages.any { it.contains("Rebuilding...") }, "saw: $messages")
    assertTrue(messages.any { it.contains("Ready 1.2s") }, "saw: $messages")
    assertTrue(messages.any { it.contains("Build failed") }, "saw: $messages")
  }

  @Test
  fun `an error status without a message falls back to the generic broadcast`() {
    val player = server.addPlayer()
    handler.handleStatus(StatusUpdate("error", null, null))
    assertTrue(drainMessages(player).any { it.contains("Build error") })
  }

  @Test
  fun `an unknown status state is ignored without throwing`() {
    handler.handleStatus(StatusUpdate("mystery", null, null))
    assertFalse(handler.blockWorldEdits)
    assertEquals(0, ipc.saveCompletions)
  }

  // ── helpers ─────────────────────────────────────────────────────────

  /**
   * Drains a mock player's queued messages to plain text so broadcasts can be asserted by content.
   */
  private fun drainMessages(player: org.mockbukkit.mockbukkit.entity.PlayerMock): List<String> =
      generateSequence { player.nextComponentMessage() }
          .map { PlainTextComponentSerializer.plainText().serialize(it) }
          .toList()

  /** Records everything the handler sends, plus the relative ordering of sends. */
  private class RecordingIpc : CompanionIpc {
    val reports = mutableListOf<HostLoadReport>()
    val progress = mutableListOf<Pair<String, String>>()
    var saveCompletions = 0
    val order = mutableListOf<String>()

    override fun sendReport(report: HostLoadReport) {
      reports += report
      order += "report"
    }

    override fun sendSaveComplete() {
      saveCompletions++
      order += "saveComplete"
    }

    override fun sendLoadProgress(requestId: String, stage: String) {
      progress += requestId to stage
      order += "progress"
    }
  }

  /** Open InnerPluginHost subclass that records dispatches for assertion. */
  private open class RecordingHost(
      server: Server,
      cl: ClassLoader,
      probe: ReflectionProbe,
      mode: LeakDiagnosticsMode = LeakDiagnosticsMode.SUMMARY,
  ) :
      InnerPluginHost(
          server,
          cl,
          probe,
          Logger.getLogger("RecordingHost"),
          leakDiagnostics = mode,
      ) {
    var dispatches: Int = 0
    var lastRequest: HostLoadRequest? = null
    var nextResult: HostLoadResult = HostLoadResult.Ok("Sample", 5)
    var isLoadedResult: Boolean = false

    override fun handleRequest(request: HostLoadRequest): HostLoadResult {
      dispatches++
      lastRequest = request
      return nextResult
    }

    override fun isLoaded(): Boolean = isLoadedResult

    override fun current() = null

    override val leakLimitReached: Boolean = false
  }

  private class FakeServerWithSpm(
      private val delegate: Server,
      private val spm: SimplePluginManager,
  ) : Server by delegate {
    override fun getPluginManager(): PluginManager = spm
  }
}

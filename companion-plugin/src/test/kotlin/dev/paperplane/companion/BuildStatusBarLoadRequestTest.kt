package dev.paperplane.companion

import com.google.gson.Gson
import dev.paperplane.companion.host.HostLoadReport
import dev.paperplane.companion.host.HostLoadRequest
import dev.paperplane.companion.host.HostLoadResult
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
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/**
 * Drives [BuildStatusBar.pollLoadRequestForTest] with file-based input and a recording fake host.
 * Verifies the contract between the CLI's load-request.json and the host:
 *
 * - Each new request id triggers a single dispatch.
 * - Repeated request ids are ignored (dedup).
 * - Failed-to-parse JSON is dropped (file deleted, no crash).
 * - The request file is consumed (deleted) after dispatch.
 * - Successful dispatches write a load-complete flag; failures write load-failed.
 */
class BuildStatusBarLoadRequestTest {

  @TempDir lateinit var tempDir: File

  private lateinit var server: ServerMock
  private lateinit var hostingPlugin: JavaPlugin
  private lateinit var fakeHost: RecordingHost
  private lateinit var bar: BuildStatusBar
  private lateinit var serverRoot: File
  private lateinit var ppDir: File
  private lateinit var fakeServer: FakeServerWithSpm
  private lateinit var probe: ReflectionProbe

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
    hostingPlugin = MockBukkit.createMockPlugin("PaperPlane")

    serverRoot = File(tempDir, "server").apply { mkdirs() }
    ppDir = File(serverRoot, ".paperplane").apply { mkdirs() }

    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    fakeServer = FakeServerWithSpm(server, spm)
    probe = ReflectionProbe.probe(fakeServer)
    fakeHost = RecordingHost(fakeServer, javaClass.classLoader, probe)

    bar = BuildStatusBar(hostingPlugin, { fakeHost }, serverRoot)
  }

  @AfterEach
  fun tearDown() {
    MockBukkit.unmock()
  }

  // ── No file → no dispatch ───────────────────────────────────────────

  @Test
  fun `poll with no load-request file is a no-op`() {
    bar.pollLoadRequestForTest()
    assertEquals(0, fakeHost.dispatches)
  }

  // ── Dispatch on first request ───────────────────────────────────────

  @Test
  fun `poll dispatches a fresh load-request and consumes the file`() {
    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()

    assertEquals(1, fakeHost.dispatches)
    assertEquals("r1", fakeHost.lastRequest!!.requestId)
    assertFalse(File(ppDir, "load-request.json").exists(), "request file must be deleted")
    assertTrue(File(ppDir, "load-complete").exists(), "successful dispatch writes load-complete")
  }

  // ── Report shape: requestId echo + strategy + atomicity ─────────────

  @Test
  fun `successful dispatch writes a structured JSON report echoing the requestId`() {
    writeRequest(HostLoadRequest(requestId = "req-42", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()

    val report = readReport("load-complete")
    assertEquals("req-42", report.requestId, "report must echo the request id for CLI matching")
    assertEquals("ok", report.status)
    // Fake host reports isLoaded()==false, so a first load is the "fresh" strategy.
    assertEquals("fresh", report.strategy)
    assertEquals("Sample", report.pluginName)
  }

  @Test
  fun `report is written atomically leaving no tmp file behind`() {
    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()

    assertTrue(File(ppDir, "load-complete").exists())
    assertFalse(
        File(ppDir, ".load-complete.tmp").exists(),
        "tmp file must be moved into place, not left behind",
    )
  }

  @Test
  fun `reload of an already-loaded plugin reports the reload strategy`() {
    fakeHost.isLoadedResult = true
    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()

    assertEquals(HostLoadReport.STRATEGY_RELOAD, readReport("load-complete").strategy)
  }

  @Test
  fun `leaks and action from the host result propagate into the written report`() {
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
    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()

    val report = readReport("load-complete")
    assertEquals(2, report.leaks!!.consecutive)
    assertEquals(3, report.leaks!!.confirmedSurvivors)
    assertEquals("timer still running", report.leaks!!.attribution.single().detail)
    assertEquals("restart", report.action)
  }

  // ── deferred leak dumps (full mode) ─────────────────────────────────

  /**
   * A leaking Ok result. [maybeDeferLeakDump] keys off [HostLoadResult.Ok.leaks] being non-null, so
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

  /** RecordingHost whose dump methods record the report-file state at the moment they run. */
  private fun dumpRecordingHost(mode: LeakDiagnosticsMode, log: MutableList<String>) =
      object : RecordingHost(fakeServer, javaClass.classLoader, probe, mode) {
        override fun dumpVerboseDiagnostics() {
          log += if (File(ppDir, "load-complete").exists()) "verbose:present" else "verbose:missing"
        }

        override fun tryDumpHeap(target: File) {
          log += if (File(ppDir, "load-complete").exists()) "heap:present" else "heap:missing"
          // The heap dump must target the server root, never user.dir.
          log += "heap-target:${target.path}"
        }
      }

  @Test
  fun `full mode defers verbose and heap dumps until after the report is on disk`() {
    val log = CopyOnWriteArrayList<String>()
    val host = dumpRecordingHost(LeakDiagnosticsMode.FULL, log).apply { nextResult = okWithLeak() }
    val deferBar = BuildStatusBar(hostingPlugin, { host }, serverRoot)

    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    deferBar.pollLoadRequestForTest()

    // The report is written synchronously; the verbose (sync) dump is queued for a later tick, so
    // it
    // must not have run yet. This is the deferral guarantee: the CLI's waiter is unblocked first.
    assertTrue(File(ppDir, "load-complete").exists(), "report must be written synchronously")
    assertFalse(log.any { it.startsWith("verbose") }, "verbose dump must be deferred, not inline")

    server.scheduler.performTicks(1)
    server.scheduler.waitAsyncTasksFinished()

    assertTrue(
        log.contains("verbose:present"),
        "verbose dump ran and saw the report already on disk",
    )
    assertTrue(log.contains("heap:present"), "heap dump ran and saw the report already on disk")
    assertFalse(log.any { it.endsWith(":missing") }, "no dump may run before the report is written")
  }

  @Test
  fun `full mode heap dump targets the server root not user dir`() {
    val log = CopyOnWriteArrayList<String>()
    val host = dumpRecordingHost(LeakDiagnosticsMode.FULL, log).apply { nextResult = okWithLeak() }
    val deferBar = BuildStatusBar(hostingPlugin, { host }, serverRoot)

    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    deferBar.pollLoadRequestForTest()
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
    val bar = BuildStatusBar(hostingPlugin, { host }, serverRoot)

    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()
    server.scheduler.performTicks(1)
    server.scheduler.waitAsyncTasksFinished()

    assertTrue(log.isEmpty(), "summary mode logs + reports attribution but never dumps; log=$log")
  }

  @Test
  fun `off mode schedules no dumps even when a leak is confirmed`() {
    val log = CopyOnWriteArrayList<String>()
    val host = dumpRecordingHost(LeakDiagnosticsMode.OFF, log).apply { nextResult = okWithLeak() }
    val bar = BuildStatusBar(hostingPlugin, { host }, serverRoot)

    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()
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
    val bar = BuildStatusBar(hostingPlugin, { host }, serverRoot)

    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()
    server.scheduler.performTicks(1)
    server.scheduler.waitAsyncTasksFinished()

    assertTrue(log.isEmpty(), "no leak → no dump, even in full mode; log=$log")
  }

  // ── claim → handle ordering ─────────────────────────────────────────

  @Test
  fun `request file is consumed before the host handles it`() {
    // A host that asserts the request file is already gone by the time it runs proves the
    // claim-then-handle ordering: the request is renamed aside before the (potentially slow)
    // handle, so a new request written mid-handling can never be deleted unseen.
    var fileGoneDuringHandle = false
    val orderingHost =
        object : RecordingHost(fakeServer, javaClass.classLoader, probe) {
          override fun handleRequest(request: HostLoadRequest): HostLoadResult {
            fileGoneDuringHandle = !File(ppDir, "load-request.json").exists()
            return super.handleRequest(request)
          }
        }
    val orderingBar = BuildStatusBar(hostingPlugin, { orderingHost }, serverRoot)
    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    orderingBar.pollLoadRequestForTest()

    assertTrue(fileGoneDuringHandle, "request file must be consumed before handleRequest runs")
  }

  @Test
  fun `request written while a previous one is being handled survives for the next poll`() {
    val midHandleWriter =
        object : RecordingHost(fakeServer, javaClass.classLoader, probe) {
          override fun handleRequest(request: HostLoadRequest): HostLoadResult {
            // Simulates the CLI writing the next request while the host is mid-handle.
            File(ppDir, "load-request.json")
                .writeText(
                    Gson()
                        .toJson(
                            HostLoadRequest(
                                requestId = "next",
                                jarPath = "/x.jar",
                                pluginName = "Sample",
                            )
                        )
                )
            return super.handleRequest(request)
          }
        }
    val racingBar = BuildStatusBar(hostingPlugin, { midHandleWriter }, serverRoot)
    writeRequest(HostLoadRequest(requestId = "first", jarPath = "/x.jar", pluginName = "Sample"))
    racingBar.pollLoadRequestForTest()

    assertTrue(
        File(ppDir, "load-request.json").exists(),
        "a request written mid-handling must survive for the next poll, not be deleted unseen",
    )
    racingBar.pollLoadRequestForTest()
    assertEquals("next", midHandleWriter.lastRequest!!.requestId)
  }

  @Test
  fun `no claim file is left behind after a poll`() {
    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()

    assertFalse(File(ppDir, ".load-request.claim").exists())
  }

  // ── Dedup on same request id ────────────────────────────────────────

  @Test
  fun `repeated requestId across polls dispatches only once`() {
    writeRequest(HostLoadRequest(requestId = "same", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()
    bar.pollLoadRequestForTest()
    bar.pollLoadRequestForTest()
    assertEquals(1, fakeHost.dispatches, "no file → nothing dispatched")

    writeRequest(HostLoadRequest(requestId = "same", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()
    assertEquals(1, fakeHost.dispatches, "duplicate id must NOT redispatch")
  }

  @Test
  fun `new requestId triggers a fresh dispatch`() {
    writeRequest(HostLoadRequest(requestId = "first", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()
    writeRequest(HostLoadRequest(requestId = "second", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()
    assertEquals(2, fakeHost.dispatches)
    assertEquals("second", fakeHost.lastRequest!!.requestId)
  }

  // ── Malformed JSON ──────────────────────────────────────────────────

  @Test
  fun `malformed json is dropped without crashing`() {
    File(ppDir, "load-request.json").writeText("{not valid json")
    bar.pollLoadRequestForTest()
    assertEquals(0, fakeHost.dispatches)
    assertFalse(
        File(ppDir, "load-request.json").exists(),
        "Invalid request must be deleted to unblock future polls.",
    )
  }

  // ── Failure path writes load-failed flag ────────────────────────────

  @Test
  fun `failed dispatch writes a structured load-failed report with the message`() {
    fakeHost.nextResult = HostLoadResult.Failed("plugin.yml not found", 12)
    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()

    assertFalse(File(ppDir, "load-complete").exists())
    val report = readReport("load-failed")
    assertEquals("r1", report.requestId)
    assertEquals("failed", report.status)
    assertEquals("plugin.yml not found", report.message)
  }

  // ── Failed branch: restart broadcast, not the old blue/green dead-end ─

  @Test
  fun `a restart-action failure broadcasts the restart notice, not the blue-green dead-end`() {
    val player = server.addPlayer()
    fakeHost.nextResult =
        HostLoadResult.Failed(
            "Hot-reload paused: accumulated classloader leaks — restarting server clears them",
            0,
            action = HostLoadReport.ACTION_RESTART,
        )
    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()

    val messages = drainMessages(player)
    assertTrue(
        messages.any { it.contains("Restarting dev server to clear leaked memory") },
        "the leak restart must be announced in-game; saw: $messages",
    )
    assertFalse(
        messages.any { it.contains("blue/green") || it.contains("Switching") },
        "the dead-end blue/green broadcast must be gone; saw: $messages",
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
    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()

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
    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()

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
    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()

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
    val lazyBar =
        BuildStatusBar(
            hostingPlugin,
            {
              built++
              fakeHost
            },
            serverRoot,
        )

    // No request yet — native modes never send one, so the host must not be built.
    lazyBar.pollLoadRequestForTest()
    assertEquals(0, built, "host must not be built without a load request")
    assertEquals(null, lazyBar.hostOrNull)

    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    lazyBar.pollLoadRequestForTest()
    assertEquals(1, built, "first load request builds the host")
    assertEquals(fakeHost, lazyBar.hostOrNull)
  }

  @Test
  fun `host provider is built once and memoized across requests`() {
    var built = 0
    val lazyBar =
        BuildStatusBar(
            hostingPlugin,
            {
              built++
              fakeHost
            },
            serverRoot,
        )

    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    lazyBar.pollLoadRequestForTest()
    writeRequest(HostLoadRequest(requestId = "r2", jarPath = "/x.jar", pluginName = "Sample"))
    lazyBar.pollLoadRequestForTest()

    assertEquals(1, built, "host must be built only once, then reused")
    assertEquals(2, fakeHost.dispatches)
  }

  @Test
  fun `probe failure writes load-failed and keeps the companion running`() {
    var attempts = 0
    val failingBar =
        BuildStatusBar(
            hostingPlugin,
            {
              attempts++
              throw UnsupportedPaperVersionException("Paper too old for hot-reload")
            },
            serverRoot,
        )

    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    failingBar.pollLoadRequestForTest()

    assertEquals(null, failingBar.hostOrNull, "no host is created on probe failure")
    val report = readReport("load-failed")
    assertEquals("r1", report.requestId)
    assertEquals("failed", report.status)
    assertEquals("Paper too old for hot-reload", report.message)
  }

  @Test
  fun `every request after a failed init is answered with load-failed echoing its own requestId`() {
    var attempts = 0
    val failingBar =
        BuildStatusBar(
            hostingPlugin,
            {
              attempts++
              throw UnsupportedPaperVersionException("Paper too old for hot-reload")
            },
            serverRoot,
        )

    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    failingBar.pollLoadRequestForTest()
    File(ppDir, "load-failed").delete()

    // The CLI's waiter filters results by requestId — a later request left unanswered (or answered
    // with a stale id) would surface as a timeout instead of the real failure.
    writeRequest(HostLoadRequest(requestId = "r2", jarPath = "/x.jar", pluginName = "Sample"))
    failingBar.pollLoadRequestForTest()

    assertEquals(1, attempts, "the init must be attempted only once, not on every request")
    val report = readReport("load-failed")
    assertEquals("r2", report.requestId, "the answer must echo the CURRENT request's id")
    assertEquals("Paper too old for hot-reload", report.message)
  }

  @Test
  fun `unexpected init exception is reported as load-failed, not swallowed`() {
    val failingBar =
        BuildStatusBar(
            hostingPlugin,
            { throw IllegalStateException("helpMap resolution blew up") },
            serverRoot,
        )

    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    // Must not throw into the scheduler tick.
    failingBar.pollLoadRequestForTest()

    val report = readReport("load-failed")
    assertEquals("r1", report.requestId)
    assertEquals("failed", report.status)
    assertTrue(report.message!!.contains("helpMap resolution blew up"))
  }

  // ── Hot-swap fast-path eligibility ──────────────────────────────────
  // tryHotSwap needs live instrumentation that isn't available in tests, so drive the pure
  // eligibility predicate directly.

  @Test
  fun `hot-swap is eligible for a method-body edit on a loaded plugin below the leak limit`() {
    assertTrue(
        bar.hotSwapEligible(
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
        bar.hotSwapEligible(
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
        bar.hotSwapEligible(false, false, listOf("com.example.Plugin"), listOf("/build/classes")),
        "not yet loaded → fresh load, not a swap",
    )
    assertFalse(
        bar.hotSwapEligible(true, false, emptyList(), listOf("/build/classes")),
        "no changed classes → structural change, needs a full reload",
    )
    assertFalse(
        bar.hotSwapEligible(true, false, listOf("com.example.Plugin"), emptyList()),
        "no classes dir → jar fallback, nothing to redefine from",
    )
  }

  // ── Build-status poll: transitions + mtime read-skip guard ──────────

  @Test
  fun `pollBuildStatus reacts to state transitions across distinct mtimes`() {
    writeStatus("building", mtime = 1_000L)
    bar.pollBuildStatusForTest()
    assertTrue(bar.blockWorldEdits, "building must block world edits")

    writeStatus("ready", mtime = 2_000L)
    bar.pollBuildStatusForTest()
    assertFalse(bar.blockWorldEdits, "ready must unblock world edits")
  }

  @Test
  fun `pollBuildStatus skips a status file whose mtime has not advanced`() {
    writeStatus("building", mtime = 5_000L)
    bar.pollBuildStatusForTest()
    assertTrue(bar.blockWorldEdits)

    // Content flips to "ready" but the mtime is unchanged: the poll short-circuits before reading,
    // so the stale state persists. This is the read-skipping guard that keeps idle polls free —
    // a genuine transition always bumps the mtime (the CLI rewrites via tmp + atomic move).
    writeStatus("ready", mtime = 5_000L)
    bar.pollBuildStatusForTest()
    assertTrue(bar.blockWorldEdits, "an unchanged mtime must skip the re-read")
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private fun writeRequest(request: HostLoadRequest) {
    File(ppDir, "load-request.json").writeText(Gson().toJson(request))
  }

  private fun writeStatus(state: String, mtime: Long) {
    val f = File(ppDir, "companion-status.json")
    f.writeText("""{"state":"$state"}""")
    check(f.setLastModified(mtime)) { "test platform could not set mtime on $f" }
  }

  /**
   * Drains a mock player's queued messages to plain text so broadcasts can be asserted by content.
   */
  private fun drainMessages(player: org.mockbukkit.mockbukkit.entity.PlayerMock): List<String> =
      generateSequence { player.nextComponentMessage() }
          .map { PlainTextComponentSerializer.plainText().serialize(it) }
          .toList()

  private fun readReport(name: String): HostLoadReport =
      Gson().fromJson(File(ppDir, name).readText(), HostLoadReport::class.java)

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

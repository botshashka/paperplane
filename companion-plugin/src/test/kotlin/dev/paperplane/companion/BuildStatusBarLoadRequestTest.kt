package dev.paperplane.companion

import com.google.gson.Gson
import dev.paperplane.companion.host.HostLoadReport
import dev.paperplane.companion.host.HostLoadRequest
import dev.paperplane.companion.host.HostLoadResult
import dev.paperplane.companion.host.InnerPluginHost
import dev.paperplane.companion.host.LeakAttribution
import dev.paperplane.companion.host.LeakSummary
import dev.paperplane.companion.host.ReflectionProbe
import java.io.File
import java.util.logging.Logger
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

    bar = BuildStatusBar(hostingPlugin, fakeHost, serverRoot)
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
    val orderingBar = BuildStatusBar(hostingPlugin, orderingHost, serverRoot)
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
    val racingBar = BuildStatusBar(hostingPlugin, midHandleWriter, serverRoot)
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

  // ── helpers ─────────────────────────────────────────────────────────

  private fun writeRequest(request: HostLoadRequest) {
    File(ppDir, "load-request.json").writeText(Gson().toJson(request))
  }

  private fun readReport(name: String): HostLoadReport =
      Gson().fromJson(File(ppDir, name).readText(), HostLoadReport::class.java)

  /** Open InnerPluginHost subclass that records dispatches for assertion. */
  private open class RecordingHost(
      server: Server,
      cl: ClassLoader,
      probe: ReflectionProbe,
  ) : InnerPluginHost(server, cl, probe, Logger.getLogger("RecordingHost")) {
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

    override val shouldForceBlueGreen: Boolean = false
  }

  private class FakeServerWithSpm(
      private val delegate: Server,
      private val spm: SimplePluginManager,
  ) : Server by delegate {
    override fun getPluginManager(): PluginManager = spm
  }
}

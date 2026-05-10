package dev.paperplane.companion

import com.google.gson.Gson
import dev.paperplane.companion.host.HostLoadRequest
import dev.paperplane.companion.host.HostLoadResult
import dev.paperplane.companion.host.InnerPluginHost
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

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
    hostingPlugin = MockBukkit.createMockPlugin("PaperPlane")

    serverRoot = File(tempDir, "server").apply { mkdirs() }
    ppDir = File(serverRoot, ".paperplane").apply { mkdirs() }

    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val fakeServer = FakeServerWithSpm(server, spm)
    val probe = ReflectionProbe.probe(fakeServer)
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
  fun `failed dispatch writes load-failed flag`() {
    fakeHost.nextResult = HostLoadResult.Failed("plugin.yml not found", 12)
    writeRequest(HostLoadRequest(requestId = "r1", jarPath = "/x.jar", pluginName = "Sample"))
    bar.pollLoadRequestForTest()

    assertFalse(File(ppDir, "load-complete").exists())
    val failed = File(ppDir, "load-failed")
    assertTrue(failed.exists())
    assertEquals("plugin.yml not found", failed.readText())
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private fun writeRequest(request: HostLoadRequest) {
    File(ppDir, "load-request.json").writeText(Gson().toJson(request))
  }

  /** Open InnerPluginHost subclass that records dispatches for assertion. */
  private class RecordingHost(
      server: Server,
      cl: ClassLoader,
      probe: ReflectionProbe,
  ) : InnerPluginHost(server, cl, probe, Logger.getLogger("RecordingHost")) {
    var dispatches: Int = 0
    var lastRequest: HostLoadRequest? = null
    var nextResult: HostLoadResult = HostLoadResult.Ok("Sample", 5)

    override fun handleRequest(request: HostLoadRequest): HostLoadResult {
      dispatches++
      lastRequest = request
      return nextResult
    }

    override fun isLoaded(): Boolean = false

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

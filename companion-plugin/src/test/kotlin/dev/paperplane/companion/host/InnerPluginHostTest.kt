package dev.paperplane.companion.host

import java.io.File
import java.net.URLClassLoader
import java.util.logging.Logger
import org.bukkit.Server
import org.bukkit.command.SimpleCommandMap
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.SimplePluginManager
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitWorker
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
 * Unit tests for [InnerPluginHost]'s state machine and validation gates.
 *
 * The full load/reload pipeline is exercised by `InnerPluginHostFullLoadTest` (which loads a real
 * JavaPlugin fixture through `DevPluginClassLoader`'s `ConfiguredPluginClassLoader` init path) and
 * end-to-end against a real Paper server in the smoke-test phase.
 */
class InnerPluginHostTest {

  @TempDir lateinit var tempDir: File

  private lateinit var server: ServerMock
  private lateinit var fakeServer: FakeServerWithSpm
  private lateinit var probe: ReflectionProbe
  private lateinit var host: InnerPluginHost

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    fakeServer = FakeServerWithSpm(server, spm)
    probe = ReflectionProbe.probe(fakeServer)
    host =
        InnerPluginHost(
            fakeServer,
            javaClass.classLoader,
            probe,
            Logger.getLogger("InnerPluginHostTest"),
        )
  }

  @AfterEach
  fun tearDown() {
    MockBukkit.unmock()
  }

  // ── state machine ───────────────────────────────────────────────────

  @Test
  fun `before any load current is null and isLoaded is false`() {
    assertNull(host.current())
    assertFalse(host.isLoaded())
  }

  @Test
  fun `shutdown without prior load is a no-op`() {
    host.shutdown()
    assertNull(host.current())
    assertFalse(host.isLoaded())
  }

  @Test
  fun `multiple shutdowns are idempotent`() {
    host.shutdown()
    host.shutdown()
    host.shutdown()
    assertNull(host.current())
  }

  // ── plugin.yml resolution failure ───────────────────────────────────

  @Test
  fun `handleRequest fails cleanly when jar does not exist`() {
    val request =
        HostLoadRequest(
            requestId = "r1",
            jarPath = File(tempDir, "nonexistent.jar").absolutePath,
            pluginName = "MyPlugin",
        )
    val result = host.handleRequest(request)
    assertTrue(result is HostLoadResult.Failed)
    val msg = (result as HostLoadResult.Failed).message
    assertTrue(msg.contains("plugin.yml"), "expected plugin.yml-not-found error, got: $msg")
    assertFalse(host.isLoaded(), "Failed load must not leave host in a loaded state")
  }

  @Test
  fun `handleRequest fails cleanly when jar has no plugin yml`() {
    val emptyJar = File(tempDir, "empty.jar")
    java.util.jar.JarOutputStream(emptyJar.outputStream()).use { /* empty */ }
    val request =
        HostLoadRequest(requestId = "r1", jarPath = emptyJar.absolutePath, pluginName = "MyPlugin")
    val result = host.handleRequest(request)
    assertTrue(result is HostLoadResult.Failed)
  }

  // ── leak-limit state machine ────────────────────────────────────────

  @Test
  fun `leakLimitReached is false initially`() {
    assertFalse(host.leakLimitReached)
  }

  @Test
  fun `leakLimitReached trips after three consecutive leaks`() {
    host.recordLeakOutcome(1)
    host.recordLeakOutcome(1)
    assertFalse(host.leakLimitReached, "two consecutive leaks must not trip the limit yet")
    host.recordLeakOutcome(1)
    assertTrue(host.leakLimitReached, "three consecutive leaks must trip the limit")
  }

  @Test
  fun `a clean reload resets the consecutive-leak streak`() {
    host.recordLeakOutcome(1)
    host.recordLeakOutcome(1)
    host.recordLeakOutcome(0) // clean reload
    host.recordLeakOutcome(1)
    host.recordLeakOutcome(1)
    assertFalse(
        host.leakLimitReached,
        "only two leaks since the clean reload — the streak must have reset",
    )
  }

  @Test
  fun `the absolute survivor cap persists across a clean reload`() {
    host.recordLeakOutcome(5) // five survivors confirmed at once
    assertTrue(host.leakLimitReached, "five confirmed survivors must trip the absolute cap")
    host.recordLeakOutcome(0) // clean reload zeroes the streak but NOT the survivor count
    assertTrue(
        host.leakLimitReached,
        "the absolute cap must survive a clean reload — that's what catches the alternating leak case",
    )
  }

  @Test
  fun `off mode keeps counting toward the cap while suppressing output`() {
    val offHost =
        InnerPluginHost(
            fakeServer,
            javaClass.classLoader,
            probe,
            Logger.getLogger("InnerPluginHostTest.off"),
            leakDiagnostics = LeakDiagnosticsMode.OFF,
        )
    assertNull(offHost.recordLeakOutcome(5), "off mode must emit no leak summary")
    assertTrue(
        offHost.leakLimitReached,
        "off gates output only — the cap still trips so the auto-restart still fires",
    )
  }

  // ── async-task excusal (still-running tasks are not leaks) ─────────

  @Test
  fun `excused reloads don't advance the consecutive-leak streak`() {
    host.recordLeakOutcome(1, allExcused = true)
    host.recordLeakOutcome(1, allExcused = true)
    host.recordLeakOutcome(1, allExcused = true)
    assertFalse(host.leakLimitReached, "excused survivors must not count toward the streak")
  }

  @Test
  fun `an excused reload skips but does not reset the streak`() {
    host.recordLeakOutcome(1)
    host.recordLeakOutcome(1)
    host.recordLeakOutcome(1, allExcused = true)
    host.recordLeakOutcome(1)
    assertTrue(
        host.leakLimitReached,
        "an excused reload between real leaks must not restart the streak",
    )
  }

  @Test
  fun `excused survivors still count toward the absolute cap`() {
    host.recordLeakOutcome(5, allExcused = true)
    assertTrue(host.leakLimitReached, "five excused survivors must still trip the absolute cap")
  }

  @Test
  fun `excused reload still emits a summary so the CLI can render the cause`() {
    val summary = host.recordLeakOutcome(1, allExcused = true)
    assertEquals(0, summary!!.consecutive, "excusal must not have advanced the streak")
    assertEquals(1, summary.confirmedSurvivors)
  }

  @Test
  fun `survivor pinned by an async worker is excused`() {
    val plugin = MockBukkit.createMockPlugin()
    val schedHost = hostWithWorkers(listOf(workerOwnedBy(plugin)))
    assertTrue(schedHost.allSurvivorsExcused(listOf(plugin.javaClass.classLoader)))
  }

  @Test
  fun `survivor with no matching worker is not excused`() {
    val plugin = MockBukkit.createMockPlugin()
    val schedHost = hostWithWorkers(listOf(workerOwnedBy(plugin)))
    assertFalse(schedHost.allSurvivorsExcused(listOf(URLClassLoader(emptyArray()))))
  }

  @Test
  fun `one unexcused survivor blocks the excusal`() {
    val plugin = MockBukkit.createMockPlugin()
    val schedHost = hostWithWorkers(listOf(workerOwnedBy(plugin)))
    assertFalse(
        schedHost.allSurvivorsExcused(
            listOf(plugin.javaClass.classLoader, URLClassLoader(emptyArray()))
        ),
        "any survivor not pinned by a worker is a real leak — excusal must not apply",
    )
  }

  @Test
  fun `no survivors means nothing to excuse`() {
    val schedHost = hostWithWorkers(emptyList())
    assertFalse(schedHost.allSurvivorsExcused(emptyList()))
  }

  // ── withDuration carry-forward ──────────────────────────────────────

  @Test
  fun `withDuration re-stamps duration but preserves leaks and action on Ok`() {
    val leaks = LeakSummary(consecutive = 1, attribution = listOf(LeakAttribution("thread", "t")))
    val restamped = HostLoadResult.Ok("P", 1, leaks, "restart").withDuration(99)

    val ok = restamped as HostLoadResult.Ok
    assertEquals(99, ok.durationMs)
    assertEquals("P", ok.pluginName)
    assertEquals(leaks, ok.leaks)
    assertEquals("restart", ok.action)
  }

  @Test
  fun `withDuration re-stamps duration but preserves leaks and action on Failed`() {
    val leaks = LeakSummary(confirmedSurvivors = 5)
    val restamped = HostLoadResult.Failed("boom", 1, leaks, "restart").withDuration(99)

    val failed = restamped as HostLoadResult.Failed
    assertEquals(99, failed.durationMs)
    assertEquals("boom", failed.message)
    assertEquals(leaks, failed.leaks)
    assertEquals("restart", failed.action)
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private fun hostWithWorkers(workers: List<BukkitWorker>): InnerPluginHost =
      InnerPluginHost(
          FakeServerWithScheduler(fakeServer, workers),
          javaClass.classLoader,
          probe,
          Logger.getLogger("InnerPluginHostTest.sched"),
      )

  private fun workerOwnedBy(plugin: Plugin): BukkitWorker =
      object : BukkitWorker {
        override fun getTaskId(): Int = 1

        override fun getOwner(): Plugin = plugin

        override fun getThread(): Thread = Thread.currentThread()
      }

  private class FakeServerWithSpm(
      private val delegate: Server,
      private val spm: SimplePluginManager,
  ) : Server by delegate {
    override fun getPluginManager(): PluginManager = spm
  }

  /** Delegating fake whose scheduler reports the given [workers] as active. */
  private class FakeServerWithScheduler(
      private val delegate: Server,
      private val workers: List<BukkitWorker>,
  ) : Server by delegate {
    override fun getScheduler(): BukkitScheduler =
        object : BukkitScheduler by delegate.scheduler {
          override fun getActiveWorkers(): List<BukkitWorker> = workers
        }
  }
}

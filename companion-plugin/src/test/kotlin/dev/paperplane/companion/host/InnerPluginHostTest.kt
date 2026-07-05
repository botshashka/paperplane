package dev.paperplane.companion.host

import java.io.File
import java.util.logging.Logger
import org.bukkit.Server
import org.bukkit.command.SimpleCommandMap
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.SimplePluginManager
import org.junit.jupiter.api.AfterEach
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
  private lateinit var host: InnerPluginHost

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val fakeServer = FakeServerWithSpm(server, spm)
    val probe = ReflectionProbe.probe(fakeServer)
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

  // ── leak detection initial state ────────────────────────────────────

  @Test
  fun `shouldForceBlueGreen is false initially`() {
    assertFalse(host.shouldForceBlueGreen)
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private class FakeServerWithSpm(
      private val delegate: Server,
      private val spm: SimplePluginManager,
  ) : Server by delegate {
    override fun getPluginManager(): PluginManager = spm
  }
}

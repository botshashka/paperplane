package dev.paperplane.companion.host

import dev.paperplane.companion.JavaPluginTransformer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.logging.Logger
import net.bytebuddy.agent.ByteBuddyAgent
import org.bukkit.Server
import org.bukkit.command.SimpleCommandMap
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.SimplePluginManager
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/**
 * Full-load test for [InnerPluginHost]. Drives the actual `loadFresh → enable → reload → shutdown`
 * pipeline with a real JavaPlugin subclass packaged into a JAR.
 *
 * **Why this exists:** the smoke test on real Paper verifies the happy path end-to-end, but unit
 * tests must catch regressions WITHOUT booting Paper. The previous unit tests only covered the
 * state machine and pre-load failures. This file adds:
 *
 * - lookupNames symmetric add/remove (the core cross-plugin compat invariant).
 * - Inner plugin's `onEnable`/`onDisable` actually running.
 * - Reload semantics: old plugin disabled before new loaded.
 * - Shutdown idempotency.
 *
 * **How it works:** ByteBuddy's agent patches `JavaPlugin.<init>()` once per JVM (matching
 * `JavaPluginBehaviorTest`'s pattern); a [TestInnerPlugin] class is packaged into a JAR fixture
 * with a fabricated `plugin.yml`; the host loads it via `DevPluginClassLoader`. We use a unique
 * plugin name and a single class so the JAR is small and the fixture is hermetic.
 */
class InnerPluginHostFullLoadTest {

  @TempDir lateinit var tempDir: File

  private lateinit var server: ServerMock
  private lateinit var spm: SimplePluginManager
  private lateinit var fakeServer: Server
  private lateinit var host: InnerPluginHost

  @BeforeEach
  fun setUp() {
    // Patch JavaPlugin once per test (idempotent — patcher's transformer is no-op if applied).
    val inst = ByteBuddyAgent.install()
    val transformer = JavaPluginTransformer()
    inst.addTransformer(transformer, true)
    try {
      inst.retransformClasses(JavaPlugin::class.java)
    } finally {
      inst.removeTransformer(transformer)
    }

    server = MockBukkit.mock()
    spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    // Modern Paper's SPM delegates enablePlugin/disablePlugin to a separate manager. Plant a tiny
    // manager that does the minimum work — call setEnabled — so our test path doesn't NPE.
    val pmField = SimplePluginManager::class.java.getDeclaredField("paperPluginManager")
    pmField.isAccessible = true
    pmField.set(spm, MinimalEnableManager(spm))

    fakeServer = FakeServerWithSpm(server, spm)
    val probe = ReflectionProbe.probe(fakeServer)
    host =
        InnerPluginHost(
            fakeServer,
            javaClass.classLoader,
            probe,
            Logger.getLogger("InnerPluginHostFullLoadTest"),
        )
    // Make InnerPluginHost use the no-arg ctor path (which runs field initializers) instead of
    // Unsafe.allocateInstance. The patcher itself ran in the @BeforeEach above; we just need to
    // flip the static flag so the host trusts the patch is in place.
    val patchedField =
        Class.forName("dev.paperplane.companion.JavaPluginPatcher").getDeclaredField("isPatched")
    patchedField.isAccessible = true
    patchedField.set(null, true)

    TestInnerPluginCounters.reset()
  }

  @AfterEach
  fun tearDown() {
    try {
      host.shutdown()
    } catch (
        @Suppress("TooGenericExceptionCaught") // Test cleanup must not mask test failures
        _: Exception) {}
    MockBukkit.unmock()
  }

  // ── Full load: instance + onEnable + lookupNames write ──────────────

  @Test
  fun `handleRequest loads plugin runs onEnable and registers in lookupNames`() {
    val request = makeLoadRequest("FullLoadOne")
    val result = host.handleRequest(request)

    assertTrue(result is HostLoadResult.Ok, "load failed: $result")
    assertEquals("FullLoadOne", (result as HostLoadResult.Ok).pluginName)

    // State machine flips.
    assertTrue(host.isLoaded())
    val inner = host.current()
    assertNotNull(inner)
    assertEquals("FullLoadOne", inner!!.description.name)

    // Inner classloader must be a DevPluginClassLoader (not the system classloader).
    assertTrue(
        inner.javaClass.classLoader.javaClass.simpleName.contains("DevPluginClassLoader"),
        "Inner plugin must be loaded by DevPluginClassLoader, was: ${inner.javaClass.classLoader.javaClass.name}",
    )

    // onEnable ran (plugin is enabled).
    assertTrue(inner.isEnabled, "pluginManager.enablePlugin must have been called")
    assertEquals(1, TestInnerPluginCounters.enableCount.get(), "onEnable must have been called once")

    // lookupNames was written symmetrically — getPlugin must return the inner.
    @Suppress("UNCHECKED_CAST")
    val lookup = readLookupNames()
    assertSame(
        inner,
        lookup["fullloadone"],
        "lookupNames must contain inner under lowercase name",
    )
  }

  // ── Shutdown: symmetric removal + onDisable ─────────────────────────

  @Test
  fun `shutdown disables inner removes from lookupNames and clears state`() {
    host.handleRequest(makeLoadRequest("ShutdownOne"))
    val inner = host.current()!!
    val before = readLookupNames()
    assertNotNull(before["shutdownone"], "precondition: lookupNames populated")

    host.shutdown()

    assertFalse(host.isLoaded(), "host state must reset")
    assertNull(host.current())
    assertEquals(1, TestInnerPluginCounters.disableCount.get(), "onDisable must have been called")
    assertFalse(inner.isEnabled, "inner must be disabled")

    val after = readLookupNames()
    assertNull(after["shutdownone"], "lookupNames must be symmetrically pruned on shutdown")
  }

  // ── Reload: old disabled + new instance ─────────────────────────────

  @Test
  fun `reload disables old instance and enables a fresh one`() {
    host.handleRequest(makeLoadRequest("ReloadOne", classSuffix = "V1"))
    val first = host.current()
    assertNotNull(first)
    assertEquals(1, TestInnerPluginCounters.enableCount.get())

    host.handleRequest(makeLoadRequest("ReloadOne", classSuffix = "V2"))
    val second = host.current()
    assertNotNull(second)

    // Different classloader instances → different Class objects → different JavaPlugin instances.
    assertTrue(first !== second, "Reload must produce a fresh JavaPlugin instance")
    assertEquals(2, TestInnerPluginCounters.enableCount.get(), "onEnable must run twice")
    assertEquals(1, TestInnerPluginCounters.disableCount.get(), "old plugin's onDisable must have run")

    // lookupNames must point at the new instance.
    val lookup = readLookupNames()
    assertSame(second, lookup["reloadone"], "lookupNames must point at the new instance")
  }

  // ── Failed load leaves no residue ───────────────────────────────────

  @Test
  fun `load failure leaves host unloaded`() {
    val bogus =
        HostLoadRequest(
            requestId = "bogus",
            jarPath = File(tempDir, "missing.jar").absolutePath,
            pluginName = "Missing",
        )
    val result = host.handleRequest(bogus)
    assertTrue(result is HostLoadResult.Failed)
    assertFalse(host.isLoaded())
    assertNull(host.current())
  }

  // ── helpers ─────────────────────────────────────────────────────────

  /**
   * Builds a JAR containing [TestInnerPlugin]'s compiled `.class` plus a `plugin.yml` that names
   * it as the main class. The JAR lands in [tempDir] so cleanup is automatic.
   */
  private fun makeLoadRequest(
      pluginName: String,
      classSuffix: String = "",
  ): HostLoadRequest {
    val jar = File(tempDir, "$pluginName-$classSuffix.jar")
    val classBytes = readClassBytes(TestInnerPlugin::class.java)

    JarOutputStream(jar.outputStream()).use { jos ->
      // Drop the class under TestInnerPlugin's actual binary name. DevPluginClassLoader is
      // child-first so it loads from this JAR before falling back to the parent classloader.
      val internalName = TestInnerPlugin::class.java.name.replace('.', '/') + ".class"
      jos.putNextEntry(JarEntry(internalName))
      jos.write(classBytes)
      jos.closeEntry()

      jos.putNextEntry(JarEntry("plugin.yml"))
      jos.write(
          """
          name: $pluginName
          main: ${TestInnerPlugin::class.java.name}
          version: 1.0
          """
              .trimIndent()
              .toByteArray(),
      )
      jos.closeEntry()
    }

    return HostLoadRequest(
        requestId = "$pluginName-$classSuffix-${System.nanoTime()}",
        jarPath = jar.absolutePath,
        pluginName = pluginName,
    )
  }

  private fun readClassBytes(cls: Class<*>): ByteArray {
    val resourceName = cls.name.replace('.', '/') + ".class"
    return cls.classLoader.getResourceAsStream(resourceName)!!.use { it.readAllBytes() }
  }

  @Suppress("UNCHECKED_CAST")
  private fun readLookupNames(): Map<String, org.bukkit.plugin.Plugin> {
    val field = SimplePluginManager::class.java.getDeclaredField("lookupNames")
    field.isAccessible = true
    return field.get(spm) as Map<String, org.bukkit.plugin.Plugin>
  }

  private class FakeServerWithSpm(
      private val delegate: Server,
      private val spm: SimplePluginManager,
  ) : Server by delegate {
    override fun getPluginManager(): PluginManager = spm
  }

  /**
   * Tiny stand-in for Paper's modern plugin manager. SPM delegates `enablePlugin`/`disablePlugin`
   * to whatever lives in `paperPluginManager`. We satisfy that contract by calling
   * `JavaPlugin.setEnabled` directly — that's what real Paper does under the hood, minus the
   * datapack/event plumbing we don't need here.
   */
  private class MinimalEnableManager(private val spm: SimplePluginManager) :
      PluginManager by spm {
    // JavaPlugin.setEnabled internally calls onEnable / onDisable on transition. We only need to
    // flip the flag — Paper's manager would also fire PluginEnable/DisableEvent, but the host
    // tests don't observe events.
    override fun enablePlugin(plugin: org.bukkit.plugin.Plugin) {
      if (plugin is JavaPlugin && !plugin.isEnabled) plugin.isEnabled = true
    }

    override fun disablePlugin(plugin: org.bukkit.plugin.Plugin) {
      if (plugin is JavaPlugin && plugin.isEnabled) plugin.isEnabled = false
    }
  }
}

/**
 * Cross-classloader counter. Lives in its own file (loaded only by the system classloader) so
 * the JAR-bundled [TestInnerPlugin] (loaded by `DevPluginClassLoader`) and the test (loaded by
 * the system classloader) see the SAME counters. If we put the counters on `TestInnerPlugin`'s
 * companion, each classloader would see its own copy and onEnable bumps would be invisible.
 */
object TestInnerPluginCounters {
  val enableCount: AtomicInteger = AtomicInteger(0)
  val disableCount: AtomicInteger = AtomicInteger(0)

  fun reset() {
    enableCount.set(0)
    disableCount.set(0)
  }
}

/**
 * The fixture plugin loaded into the host. References [TestInnerPluginCounters] (NOT bundled in
 * our JAR), which the child classloader resolves through its parent — keeping the counter shared
 * across both loads of TestInnerPlugin (the system one in test classpath, and the one loaded by
 * DevPluginClassLoader from the JAR).
 */
class TestInnerPlugin : JavaPlugin() {
  override fun onEnable() {
    TestInnerPluginCounters.enableCount.incrementAndGet()
  }

  override fun onDisable() {
    TestInnerPluginCounters.disableCount.incrementAndGet()
  }
}

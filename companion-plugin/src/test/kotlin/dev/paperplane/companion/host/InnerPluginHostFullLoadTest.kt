package dev.paperplane.companion.host

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.command.SimpleCommandMap
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapedRecipe
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
 * **How it works:** a [TestInnerPlugin] class is packaged into a JAR fixture with a fabricated
 * `plugin.yml`; the host loads it via `DevPluginClassLoader`, whose `ConfiguredPluginClassLoader`
 * implementation makes the real, unpatched `JavaPlugin` ctor initialize the instance. We use a
 * unique plugin name and a single class so the JAR is small and the fixture is hermetic.
 */
class InnerPluginHostFullLoadTest {

  @TempDir lateinit var tempDir: File

  private lateinit var server: ServerMock
  private lateinit var spm: SimplePluginManager
  private lateinit var paperManager: MinimalEnableManager
  private lateinit var fakeServer: Server
  private lateinit var host: InnerPluginHost

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
    spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    // Modern Paper's SPM delegates enablePlugin/disablePlugin to a separate manager. Plant a tiny
    // manager that does the minimum work — call setEnabled — so our test path doesn't NPE.
    paperManager = MinimalEnableManager(spm)
    val pmField = SimplePluginManager::class.java.getDeclaredField("paperPluginManager")
    pmField.isAccessible = true
    pmField.set(spm, paperManager)

    fakeServer = FakeServerWithSpm(server, spm)
    val probe = ReflectionProbe.probe(fakeServer)
    host =
        InnerPluginHost(
            fakeServer,
            javaClass.classLoader,
            probe,
            Logger.getLogger("InnerPluginHostFullLoadTest"),
        )
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
    assertEquals(
        1,
        TestInnerPluginCounters.enableCount.get(),
        "onEnable must have been called once",
    )

    // lookupNames was written symmetrically — getPlugin must return the inner.
    @Suppress("UNCHECKED_CAST") val lookup = readLookupNames()
    assertSame(
        inner,
        lookup["fullloadone"],
        "lookupNames must contain inner under lowercase name",
    )

    // The Paper-side map is the one getPlugin(name) actually reads on real Paper — it must have
    // been written too, and getPlugin must resolve through it end-to-end.
    assertSame(
        inner,
        paperManager.lookupNames["fullloadone"],
        "Paper manager's own lookupNames must contain the inner plugin",
    )
    assertSame(
        inner,
        fakeServer.pluginManager.getPlugin("FullLoadOne"),
        "cross-plugin getPlugin(name) must resolve the inner plugin",
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
    assertNull(
        paperManager.lookupNames["shutdownone"],
        "Paper manager's lookupNames must be symmetrically pruned on shutdown",
    )
  }

  @Test
  fun `teardown releases chunk tickets and the plugin's keyed boss bars`() {
    host.handleRequest(makeLoadRequest("TicketBar"))
    val inner = host.current()!!
    val world = server.addSimpleWorld("ticketbar-world")
    world.addPluginChunkTicket(0, 0, inner)
    val innerKey = NamespacedKey(inner, "progress")
    server.createBossBar(innerKey, "Progress", BarColor.BLUE, BarStyle.SOLID)
    val foreignKey = NamespacedKey.minecraft("foreign-bar")
    server.createBossBar(foreignKey, "Foreign", BarColor.RED, BarStyle.SOLID)

    host.shutdown()

    assertTrue(
        world.getPluginChunkTickets(0, 0).isEmpty(),
        "chunk tickets keep chunks force-loaded — teardown must release them",
    )
    assertNull(
        server.getBossBar(innerKey),
        "the inner plugin's keyed boss bars must be removed on teardown",
    )
    assertNotNull(
        server.getBossBar(foreignKey),
        "boss bars from other namespaces must be untouched",
    )
  }

  @Test
  fun `teardown removes the plugin's keyed recipes but leaves foreign ones`() {
    host.handleRequest(makeLoadRequest("RecipeCook"))
    val inner = host.current()!!
    val innerKey = NamespacedKey(inner, "gadget")
    server.addRecipe(
        ShapedRecipe(innerKey, ItemStack(Material.DIAMOND)).apply {
          shape("X")
          setIngredient('X', Material.STICK)
        }
    )
    val foreignKey = NamespacedKey.fromString("other:foreign_gadget")!!
    server.addRecipe(
        ShapedRecipe(foreignKey, ItemStack(Material.DIAMOND)).apply {
          shape("Y")
          setIngredient('Y', Material.STICK)
        }
    )

    host.shutdown()

    assertNull(
        server.getRecipe(innerKey),
        "the inner plugin's keyed recipes must be removed on teardown — a duplicate key on the " +
            "next reload is silently ignored, so recipe edits would never take effect",
    )
    assertNotNull(
        server.getRecipe(foreignKey),
        "recipes from other namespaces must be untouched",
    )
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
    assertEquals(
        1,
        TestInnerPluginCounters.disableCount.get(),
        "old plugin's onDisable must have run",
    )

    // lookupNames must point at the new instance.
    val lookup = readLookupNames()
    assertSame(second, lookup["reloadone"], "lookupNames must point at the new instance")
  }

  // ── Reload rejects NMS-using plugins ────────────────────────────────

  @Test
  fun `reload is rejected when usesNmsClasses returns true`() {
    // Build a host that pretends every loaded inner plugin touches NMS. The first load is a
    // FRESH load — usesNmsClasses isn't checked there. The SECOND request enters reload(), which
    // calls usesNmsClasses(active.plugin) before any teardown and must abort with a clear message.
    val nmsHost =
        object :
            InnerPluginHost(
                fakeServer,
                javaClass.classLoader,
                ReflectionProbe.probe(fakeServer),
                Logger.getLogger("InnerPluginHostFullLoadTest.nms"),
            ) {
          override fun usesNmsClasses(plugin: JavaPlugin): Boolean = true
        }

    val first = nmsHost.handleRequest(makeLoadRequest("NmsCanary", classSuffix = "V1"))
    assertTrue(first is HostLoadResult.Ok, "fresh load should succeed regardless of NMS heuristic")
    val firstInstance = nmsHost.current()
    assertNotNull(firstInstance)

    val second = nmsHost.handleRequest(makeLoadRequest("NmsCanary", classSuffix = "V2"))
    assertTrue(second is HostLoadResult.Failed, "reload must be rejected for NMS-using plugins")
    assertTrue(
        (second as HostLoadResult.Failed).message.contains("NMS"),
        "rejection message should mention NMS, got: ${second.message}",
    )

    // The original instance must still be live — rejection happens BEFORE teardown.
    assertSame(firstInstance, nmsHost.current(), "old instance must remain after reload rejection")
    assertTrue(firstInstance!!.isEnabled, "old plugin must remain enabled after reload rejection")
    assertEquals(1, TestInnerPluginCounters.enableCount.get(), "no second onEnable")
    assertEquals(0, TestInnerPluginCounters.disableCount.get(), "no onDisable on rejection")

    nmsHost.shutdown()
  }

  // ── Leak-limit auto-restart signalling ──────────────────────────────

  @Test
  fun `reload refused past the leak limit reports Failed with a restart action`() {
    host.handleRequest(makeLoadRequest("LimitRefuse", classSuffix = "V1"))
    // Force the absolute survivor cap so the next reload is refused at the top of reload().
    host.recordLeakOutcome(5)

    val result = host.handleRequest(makeLoadRequest("LimitRefuse", classSuffix = "V2"))

    assertTrue(
        result is HostLoadResult.Failed,
        "reload must be refused once the leak limit is reached, got: $result",
    )
    val failed = result as HostLoadResult.Failed
    assertEquals(
        HostLoadReport.ACTION_RESTART,
        failed.action,
        "the refusal must carry the restart action (belt-and-braces if the CLI missed the tripping Ok)",
    )
    assertTrue(
        failed.message.contains("restart", ignoreCase = true),
        "the refusal message should explain the restart, got: ${failed.message}",
    )
    // The old instance stays live — the refusal happens before any teardown.
    assertTrue(host.isLoaded())
  }

  @Test
  fun `a reload that trips the leak limit reports Ok with a restart action`() {
    // The GC-driven survivor count is non-deterministic, so inject a tripping count via the
    // (protected) checkForLeaks seam. reload() still runs for real end-to-end; only the leak scan
    // is
    // scripted, which is exactly the production wiring under test: Ok + action when
    // leakLimitReached.
    val trippingHost =
        object :
            InnerPluginHost(
                fakeServer,
                javaClass.classLoader,
                ReflectionProbe.probe(fakeServer),
                Logger.getLogger("InnerPluginHostFullLoadTest.trip"),
            ) {
          override fun checkForLeaks(): LeakSummary? = recordLeakOutcome(5)
        }

    trippingHost.handleRequest(makeLoadRequest("LimitTrip", classSuffix = "V1"))
    val result = trippingHost.handleRequest(makeLoadRequest("LimitTrip", classSuffix = "V2"))

    assertTrue(
        result is HostLoadResult.Ok,
        "the reload itself succeeds — the leak limit trips ON that Ok, got: $result",
    )
    assertEquals(HostLoadReport.ACTION_RESTART, (result as HostLoadResult.Ok).action)

    trippingHost.shutdown()
  }

  // ── Failed load leaves no residue ───────────────────────────────────

  @Test
  fun `reload failure after teardown leaves host unloaded and recovers via fresh load`() {
    host.handleRequest(makeLoadRequest("ReloadBreak", classSuffix = "V1"))
    assertTrue(host.isLoaded())
    assertEquals(0, TestInnerPluginCounters.disableCount.get())

    // Reload with a main class that throws from its constructor: readDescription + validate pass,
    // so teardown runs (old plugin gone), then loadPlugin's newInstance throws — the post-teardown
    // failure window. The host must be left honestly unloaded, not pointing at the dead instance.
    val result = host.handleRequest(explodingRequest("ReloadBreak"))

    assertTrue(
        result is HostLoadResult.Failed,
        "a post-teardown load failure must report Failed, got: $result",
    )
    assertFalse(host.isLoaded(), "a failed reload after teardown must leave the host unloaded")
    assertNull(host.current())
    assertEquals(
        1,
        TestInnerPluginCounters.disableCount.get(),
        "the old plugin's onDisable ran exactly once during teardown",
    )

    // Recovery: because active was reset to null, the next good request routes through loadFresh
    // (no second teardown of the already-dead loader) and succeeds.
    val recovered = host.handleRequest(makeLoadRequest("ReloadBreak", classSuffix = "V3"))
    assertTrue(
        recovered is HostLoadResult.Ok,
        "host must recover via a fresh load after a failed reload, got: $recovered",
    )
    assertTrue(host.isLoaded())
    assertEquals(1, TestInnerPluginCounters.disableCount.get(), "no extra teardown on recovery")
  }

  @Test
  fun `a plugin_yml naming a missing main class fails cleanly instead of crashing`() {
    // The user's plugin.yml points at a class that isn't in the build output (typo'd or renamed
    // main). loadClass misses in our URLs, in the parent, and across every other plugin's loader —
    // that search must terminate in a readable ClassNotFoundException, not a StackOverflowError.
    val result = host.handleRequest(brokenMainRequest("MissingMain"))

    assertTrue(result is HostLoadResult.Failed, "a missing main class must report Failed: $result")
    assertTrue(
        (result as HostLoadResult.Failed).message.contains("NoSuchInnerPlugin"),
        "the failure must name the class that could not be found, got: ${result.message}",
    )
    assertFalse(host.isLoaded())
  }

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

  // ── Verbose leak diagnostics (LeakDiagnosticsMode.FULL path) ────────

  @Test
  fun `dumpVerboseDiagnostics is a no-op when nothing has leaked`() {
    withCapturedLog { captured ->
      host.handleRequest(makeLoadRequest("NoSurvivors"))

      host.dumpVerboseDiagnostics()

      assertTrue(
          captured.none { it.contains("leak diagnostics") },
          "a single load leaks nothing — the dump must stay silent, got: $captured",
      )
    }
  }

  @Test
  fun `dumpVerboseDiagnostics reports each surviving leaked loader`() {
    withCapturedLog { captured ->
      val v1 = threeGenerationsPinningTheFirst()

      host.dumpVerboseDiagnostics()

      assertTrue(
          captured.any { it.contains("leak diagnostics") && it.contains("surviving loader") },
          "must announce the surviving loader count, got: $captured",
      )
      assertTrue(captured.any { it.contains("loader#0") }, "must tag each survivor")
      assertTrue(captured.any { it.contains("pluginManager.plugins scan") })
      assertTrue(captured.any { it.contains("commandMap.knownCommands scan") })
      assertTrue(captured.any { it.contains("getInitiatedClasses") })
      assertNotNull(v1, "keep the pinning reference alive until after the dump")
    }
  }

  @Test
  fun `dumpVerboseDiagnostics attributes a thread still rooted in a surviving loader`() {
    withCapturedLog { captured ->
      val v1 = threeGenerationsPinningTheFirst()
      // Start the pinning thread AFTER the reloads so teardown's interruptPluginThreads can't
      // reap it; it holds the old loader as its contextClassLoader exactly like a real leak.
      val release = CountDownLatch(1)
      val pinner =
          Thread { release.await() }
              .apply {
                name = "pinning-thread"
                isDaemon = true
                contextClassLoader = v1.javaClass.classLoader
                start()
              }
      try {
        host.dumpVerboseDiagnostics()

        assertTrue(
            captured.any { it.contains("thread(s) with this contextClassLoader") },
            "a thread rooted in the survivor must be attributed, got: $captured",
        )
        assertTrue(captured.any { it.contains("pinning-thread") }, "must name the pinning thread")
      } finally {
        release.countDown()
        pinner.join(1_000)
      }
    }
  }

  /**
   * Drives three load generations and returns the first plugin instance. Holding it strongly pins
   * its classloader, so it survives [checkForLeaks]'s GC and lands in the survivor set — the
   * most-recently-torn-down loader is excluded by the dump's `dropLast(1)`, hence three
   * generations.
   */
  private fun threeGenerationsPinningTheFirst(): JavaPlugin {
    host.handleRequest(makeLoadRequest("VerboseDump", classSuffix = "V1"))
    val v1 = host.current()!!
    host.handleRequest(makeLoadRequest("VerboseDump", classSuffix = "V2"))
    host.handleRequest(makeLoadRequest("VerboseDump", classSuffix = "V3"))
    return v1
  }

  /** Runs [body] with a handler capturing everything the host logs, then detaches it. */
  private fun withCapturedLog(body: (List<String>) -> Unit) {
    val logger = Logger.getLogger("InnerPluginHostFullLoadTest")
    val handler = CapturingHandler()
    logger.addHandler(handler)
    try {
      body(handler.messages)
    } finally {
      logger.removeHandler(handler)
    }
  }

  private class CapturingHandler : Handler() {
    val messages: MutableList<String> = CopyOnWriteArrayList()

    override fun publish(record: LogRecord) {
      messages += record.message
    }

    override fun flush() {}

    override fun close() {}
  }

  // ── helpers ─────────────────────────────────────────────────────────

  /**
   * Builds a JAR containing [TestInnerPlugin]'s compiled `.class` plus a `plugin.yml` that names it
   * as the main class. The JAR lands in [tempDir] so cleanup is automatic.
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

  /**
   * Builds a JAR whose main class ([TestExplodingPlugin]) loads fine but throws from its
   * constructor, so `readDescription` + `validate` pass and teardown runs, then `loadPlugin`'s
   * `newInstance` throws — reproducing the post-teardown reload-failure window (an edited main
   * class that blows up on instantiation).
   */
  private fun explodingRequest(pluginName: String): HostLoadRequest {
    val jar = File(tempDir, "$pluginName-boom.jar")
    val classBytes = readClassBytes(TestExplodingPlugin::class.java)
    JarOutputStream(jar.outputStream()).use { jos ->
      val internalName = TestExplodingPlugin::class.java.name.replace('.', '/') + ".class"
      jos.putNextEntry(JarEntry(internalName))
      jos.write(classBytes)
      jos.closeEntry()

      jos.putNextEntry(JarEntry("plugin.yml"))
      jos.write(
          """
          name: $pluginName
          main: ${TestExplodingPlugin::class.java.name}
          version: 1.0
          """
              .trimIndent()
              .toByteArray(),
      )
      jos.closeEntry()
    }
    return HostLoadRequest(
        requestId = "$pluginName-boom-${System.nanoTime()}",
        jarPath = jar.absolutePath,
        pluginName = pluginName,
    )
  }

  /**
   * Builds a JAR with a valid `plugin.yml` naming a main class that is NOT in the JAR, so
   * `readDescription` + `validate` pass but `loadPlugin`'s `loadClass` misses everywhere — the
   * class-not-found path through the cross-plugin fallback.
   */
  private fun brokenMainRequest(pluginName: String): HostLoadRequest {
    val jar = File(tempDir, "$pluginName-broken.jar")
    JarOutputStream(jar.outputStream()).use { jos ->
      jos.putNextEntry(JarEntry("plugin.yml"))
      jos.write(
          """
          name: $pluginName
          main: dev.paperplane.companion.host.NoSuchInnerPlugin
          version: 1.0
          """
              .trimIndent()
              .toByteArray(),
      )
      jos.closeEntry()
    }
    return HostLoadRequest(
        requestId = "$pluginName-broken-${System.nanoTime()}",
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
   *
   * Like real Paper's `PaperPluginInstanceManager`, it keeps its OWN `lookupNames` map and resolves
   * `getPlugin(name)` from it — NOT from SPM's map. This models the empirically-verified 1.21.11
   * behavior that the host's dual-write exists for: an SPM-only registration must make these tests
   * fail the cross-plugin lookup, exactly as it does on a real server.
   */
  private class MinimalEnableManager(spm: SimplePluginManager) : PluginManager by spm {
    /** Resolved by the probe via generic signature — same shape as Paper's instance manager. */
    val lookupNames: MutableMap<String, org.bukkit.plugin.Plugin> = HashMap()

    // JavaPlugin.setEnabled internally calls onEnable / onDisable on transition. We only need to
    // flip the flag — Paper's manager would also fire PluginEnable/DisableEvent, but the host
    // tests don't observe events.
    override fun enablePlugin(plugin: org.bukkit.plugin.Plugin) {
      if (plugin is JavaPlugin && !plugin.isEnabled) plugin.isEnabled = true
    }

    override fun disablePlugin(plugin: org.bukkit.plugin.Plugin) {
      if (plugin is JavaPlugin && plugin.isEnabled) plugin.isEnabled = false
    }

    override fun getPlugin(name: String): org.bukkit.plugin.Plugin? = lookupNames[name.lowercase()]

    /**
     * Resolve from our own map, exactly like Paper's instance manager does. This override is load-
     * bearing, not a convenience: `SimplePluginManager.getPlugins()` delegates to whatever sits in
     * `paperPluginManager` — which is this object — so inheriting the `by spm` delegation would
     * bounce the two off each other until the stack overflows.
     */
    override fun getPlugins(): Array<org.bukkit.plugin.Plugin> = lookupNames.values.toTypedArray()
  }
}

/**
 * Cross-classloader counter. Lives in its own file (loaded only by the system classloader) so the
 * JAR-bundled [TestInnerPlugin] (loaded by `DevPluginClassLoader`) and the test (loaded by the
 * system classloader) see the SAME counters. If we put the counters on `TestInnerPlugin`'s
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
 * The fixture plugin loaded into the host. References [TestInnerPluginCounters] (NOT bundled in our
 * JAR), which the child classloader resolves through its parent — keeping the counter shared across
 * both loads of TestInnerPlugin (the system one in test classpath, and the one loaded by
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

/**
 * A fixture whose constructor throws after the `JavaPlugin` super-ctor has run — models an edited
 * main class that compiles and loads but blows up on instantiation, driving the host's
 * post-teardown reload-failure path.
 */
class TestExplodingPlugin : JavaPlugin() {
  init {
    error("boom in inner plugin constructor")
  }
}

package dev.paperplane.companion.host

import dev.paperplane.companion.DevPluginClassLoader
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.command.SimpleCommandMap
import org.bukkit.help.HelpMap
import org.bukkit.help.HelpTopic
import org.bukkit.help.HelpTopicFactory
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.SimplePluginManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class ReflectionProbeTest {

  private lateinit var server: ServerMock

  @BeforeEach
  fun setUp() {
    server = MockBukkit.mock()
  }

  @AfterEach
  fun tearDown() {
    MockBukkit.unmock()
  }

  // ── unwrapSpm ────────────────────────────────────────────────────────

  @Test
  fun `unwrapSpm returns plugin manager when it IS SimplePluginManager`() {
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val unwrapped = ReflectionProbe.unwrapSpm(spm)
    assertSame(spm, unwrapped)
  }

  @Test
  fun `unwrapSpm finds SPM nested inside a wrapper`() {
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val wrapper = WrapperPluginManager(spm)
    val unwrapped = ReflectionProbe.unwrapSpm(wrapper)
    assertSame(spm, unwrapped)
  }

  // ── full probe ───────────────────────────────────────────────────────

  @Test
  fun `probe fails fast when plugin manager has no SPM`() {
    // MockBukkit's PluginManagerMock isn't a SimplePluginManager and doesn't wrap one,
    // so the probe must reject this rather than limp along.
    val ex =
        assertThrows(UnsupportedPaperVersionException::class.java) { ReflectionProbe.probe(server) }
    assertTrue(
        ex.message!!.contains("SimplePluginManager.lookupNames"),
        "Error must point at the failed reflection point, got: ${ex.message}",
    )
    assertTrue(
        ex.message!!.contains("github.com/botshashka/paperplane"),
        "Error must include issue tracker URL for debugging",
    )
  }

  @Test
  fun `probe succeeds against a fake server backed by real SPM`() {
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val fakeServer = FakeServerWithSpm(server, spm)

    val probe = ReflectionProbe.probe(fakeServer)

    // SPM lookupNames is the load-bearing reflection point.
    assertNotNull(probe.spmLookupNamesField)
    assertEquals("lookupNames", probe.spmLookupNamesField.name)
  }

  // ── CPCL integration guard ──────────────────────────────────────────

  @Test
  fun `DevPluginClassLoader concretely implements every ConfiguredPluginClassLoader method`() {
    // Guard (d): the probe rejects a runtime where DevPluginClassLoader doesn't override an
    // interface method. Assert directly that, on the test classpath, every method of the runtime
    // interface resolves to a non-abstract method on DevPluginClassLoader — the same check the
    // probe runs. Simulating the interface's ABSENCE isn't feasible (it's on the classpath), so we
    // pin the positive contract that makes the probe succeed here.
    val cpcl =
        Class.forName(
            "io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader",
            false,
            org.bukkit.plugin.java.JavaPlugin::class.java.classLoader,
        )
    for (m in cpcl.methods) {
      val impl = DevPluginClassLoader::class.java.getMethod(m.name, *m.parameterTypes)
      assertFalse(
          java.lang.reflect.Modifier.isAbstract(impl.modifiers),
          "DevPluginClassLoader.${m.name} must be concrete; got abstract",
      )
    }
  }

  @Test
  fun `probe succeeds on this Paper version - CPCL guard passes`() {
    // The full probe runs the CPCL guard first; on paper-api that supports the plugin-loader API
    // (our test classpath) it must not add version-floor errors and the probe must complete.
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val probe = ReflectionProbe.probe(FakeServerWithSpm(server, spm))
    assertNotNull(probe.spmLookupNamesField)
    assertNotNull(probe.helpTopics)
  }

  // ── Paper lookupNames resolution ────────────────────────────────────

  @Test
  fun `paperLookupNames resolves the Paper manager's own map`() {
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val paperManager = FakePaperManagerWithMap(spm)
    plantPaperManager(spm, paperManager)

    val probe = ReflectionProbe.probe(FakeServerWithSpm(server, spm))

    assertSame(
        paperManager.lookupNames,
        probe.paperLookupNames,
        "probe must resolve the Paper manager's OWN lookup map — not SPM's",
    )
  }

  @Test
  fun `paperLookupNames resolves a map nested one level deep`() {
    // Real Paper keeps the map on PaperPluginManagerImpl's instance manager, one field level down.
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val paperManager = FakePaperManagerNested(spm)
    plantPaperManager(spm, paperManager)

    val probe = ReflectionProbe.probe(FakeServerWithSpm(server, spm))

    assertSame(paperManager.instanceManager.lookupNames, probe.paperLookupNames)
  }

  @Test
  fun `paperLookupNames is null when no Paper manager is planted`() {
    // Unit tests and legacy runtimes: SPM's own map is authoritative; no error, no Paper map.
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val probe = ReflectionProbe.probe(FakeServerWithSpm(server, spm))
    assertTrue(probe.paperLookupNames == null, "no paperPluginManager planted → null")
  }

  @Test
  fun `probe fails when the Paper manager exposes no lookup map`() {
    // A Paper manager IS present but its shape changed — silent SPM-only registration would break
    // cross-plugin getPlugin(name), so the probe must refuse loudly.
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    plantPaperManager(spm, FakePaperManagerWithoutMap(spm))

    val ex =
        assertThrows(UnsupportedPaperVersionException::class.java) {
          ReflectionProbe.probe(FakeServerWithSpm(server, spm))
        }
    assertTrue(
        ex.message!!.contains("no Map<String, Plugin>"),
        "Error must point at the missing lookup map, got: ${ex.message}",
    )
  }

  // ── Brigadier lifecycle-command resolution ──────────────────────────

  @Test
  fun `lifecycleCommandSync is null on a runtime without CraftServer command machinery`() {
    // MockBukkit's test classpath HAS the lifecycle-command API (paper-api + the provider mock),
    // but the server is not CraftServer-shaped (no syncCommands) — the feature must resolve to
    // "unavailable" silently, not to a probe error, or every unit-test probe would explode.
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val probe = ReflectionProbe.probe(FakeServerWithSpm(server, spm))
    assertTrue(probe.lifecycleCommandSync == null, "no CraftServer marker → feature unavailable")
  }

  @Test
  fun `probe fails when the server looks like CraftServer but lifecycle internals are missing`() {
    // A server WITH syncCommands and a classpath WITH the lifecycle-command API is "modern Paper"
    // as far as the probe can tell — but paper-server's LifecycleEventRunner isn't on the test
    // classpath, modeling a Paper that moved the re-collection machinery. Silently skipping would
    // leave Brigadier commands unregistered and stale nodes alive across reloads, so the probe
    // must refuse loudly.
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val ex =
        assertThrows(UnsupportedPaperVersionException::class.java) {
          ReflectionProbe.probe(FakeCraftLikeServer(server, spm))
        }
    assertTrue(
        ex.message!!.contains("Brigadier lifecycle-command internals"),
        "Error must point at the failed resolution, got: ${ex.message}",
    )
  }

  // ── Field/method shape pinning ──────────────────────────────────────

  @Test
  fun `lookupNames field is a Map`() {
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val probe = ReflectionProbe.probe(FakeServerWithSpm(server, spm))

    val f = probe.spmLookupNamesField
    assertTrue(
        java.util.Map::class.java.isAssignableFrom(f.type),
        "lookupNames must be a Map; SPM signature changed otherwise. Got: ${f.type}",
    )
    assertTrue(f.isAccessible, "lookupNames must be made accessible at probe time")
  }

  @Test
  fun `helpTopics is the live backing map of the HelpMap`() {
    // Mutations to the probed map must be visible via the HelpMap's public API, otherwise direct
    // writes wouldn't reach `/help`.
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val probe = ReflectionProbe.probe(FakeServerWithSpm(server, spm))

    val topic = StubHelpTopic("/probe-check")
    probe.helpTopics["/probe-check"] = topic
    assertSame(topic, server.helpMap.getHelpTopic("/probe-check"))
  }

  @Test
  fun `probe fails fast when helpMap has no Map of String to HelpTopic field`() {
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val fakeServer = FakeServerWithCustomHelpMap(server, spm, HelpMapWithoutTopicsField())

    val ex =
        assertThrows(UnsupportedPaperVersionException::class.java) {
          ReflectionProbe.probe(fakeServer)
        }
    assertTrue(
        ex.message!!.contains("SimpleHelpMap.helpTopics"),
        "Error must point at the failed reflection point, got: ${ex.message}",
    )
    assertTrue(
        ex.message!!.contains("github.com/botshashka/paperplane"),
        "Error must include issue tracker URL for debugging",
    )
  }

  // ── helpers ──────────────────────────────────────────────────────────

  /** A wrapper PluginManager that holds an SPM in a private field. */
  private class WrapperPluginManager(@Suppress("unused") private val delegate: PluginManager) :
      PluginManager by delegate

  private fun plantPaperManager(spm: SimplePluginManager, manager: PluginManager) {
    val f = SimplePluginManager::class.java.getDeclaredField("paperPluginManager")
    f.isAccessible = true
    f.set(spm, manager)
  }

  /** Models Paper's manager shape: a `Map<String, Plugin>` lookup field of its own. */
  private class FakePaperManagerWithMap(delegate: PluginManager) : PluginManager by delegate {
    val lookupNames: MutableMap<String, org.bukkit.plugin.Plugin> = HashMap()
  }

  /** Map lives one level down, like Paper's `PaperPluginManagerImpl.instanceManager`. */
  private class FakePaperManagerNested(delegate: PluginManager) : PluginManager by delegate {
    val instanceManager = FakeInstanceManager()
  }

  private class FakeInstanceManager {
    val lookupNames: MutableMap<String, org.bukkit.plugin.Plugin> = HashMap()
  }

  /** A Paper-manager stand-in with NO lookup map anywhere — drives the probe error path. */
  private class FakePaperManagerWithoutMap(delegate: PluginManager) : PluginManager by delegate {
    @Suppress("unused") private val unrelated: MutableList<String> = mutableListOf()
  }

  /** Server that returns a real SPM for `pluginManager` so the probe completes end-to-end. */
  private class FakeServerWithSpm(
      private val delegate: Server,
      private val spm: SimplePluginManager,
  ) : Server by delegate {
    override fun getPluginManager(): PluginManager = spm
  }

  /**
   * Server that passes the probe's "modern Paper" discriminator: a public `syncCommands` method
   * (the CraftServer marker) on a classpath where the lifecycle-command API resolves.
   */
  private class FakeCraftLikeServer(
      private val delegate: Server,
      private val spm: SimplePluginManager,
  ) : Server by delegate {
    override fun getPluginManager(): PluginManager = spm

    @Suppress("unused") // resolved reflectively by the probe's discriminator
    fun syncCommands() = Unit
  }

  /** Server with a real SPM and a custom HelpMap implementation. */
  private class FakeServerWithCustomHelpMap(
      private val delegate: Server,
      private val spm: SimplePluginManager,
      private val helpMap: HelpMap,
  ) : Server by delegate {
    override fun getPluginManager(): PluginManager = spm

    override fun getHelpMap(): HelpMap = helpMap
  }

  /** HelpMap implementation with no `Map<String, HelpTopic>` field, to drive the failure path. */
  private class HelpMapWithoutTopicsField : HelpMap {
    @Suppress("unused") private val unrelated: String = "decoy"

    override fun getHelpTopic(topicName: String): HelpTopic? = null

    override fun getHelpTopics(): Collection<HelpTopic> = emptyList()

    override fun addTopic(topic: HelpTopic) = Unit

    override fun clear() = Unit

    override fun registerHelpTopicFactory(
        commandClass: Class<*>,
        factory: HelpTopicFactory<*>,
    ) = Unit

    override fun getIgnoredPlugins(): List<String> = emptyList()
  }

  /** Minimal HelpTopic for probe-level tests. */
  private class StubHelpTopic(topicName: String) : HelpTopic() {
    init {
      name = topicName
      shortText = ""
    }

    override fun canSee(player: CommandSender): Boolean = true
  }
}

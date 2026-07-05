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

  /** Server that returns a real SPM for `pluginManager` so the probe completes end-to-end. */
  private class FakeServerWithSpm(
      private val delegate: Server,
      private val spm: SimplePluginManager,
  ) : Server by delegate {
    override fun getPluginManager(): PluginManager = spm
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

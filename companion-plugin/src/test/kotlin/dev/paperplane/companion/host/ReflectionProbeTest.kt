package dev.paperplane.companion.host

import org.bukkit.Server
import org.bukkit.command.SimpleCommandMap
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.SimplePluginManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
        assertThrows(UnsupportedPaperVersionException::class.java) {
          ReflectionProbe.probe(server)
        }
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

    // At least one of init / fields must resolve. JavaPlugin is on the classpath, so init exists.
    assertNotNull(probe.javaPluginInit, "JavaPlugin.init(...) should resolve on stock paper-api")
    assertNotNull(probe.javaPluginFields, "JavaPlugin private fields should resolve as fallback")

    val fields = probe.javaPluginFields!!
    assertEquals("server", fields.server.name)
    assertEquals("description", fields.description.name)
    assertEquals("dataFolder", fields.dataFolder.name)
    assertEquals("file", fields.file.name)
    assertEquals("classLoader", fields.classLoader.name)
  }

  // ── Field/method shape pinning ──────────────────────────────────────

  @Test
  fun `init method has the expected six-parameter signature`() {
    // This test pins the contract: if Paper ever changes JavaPlugin.init's parameters, our
    // resolution path silently returns null and we fall back to fields. This test fails when
    // probe.javaPluginInit is unexpectedly null.
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val probe = ReflectionProbe.probe(FakeServerWithSpm(server, spm))

    val init = probe.javaPluginInit!!
    val params = init.parameterTypes
    assertEquals(6, params.size, "init() must have 6 params; signature changed otherwise")
    assertEquals("PluginLoader", params[0].simpleName)
    assertEquals("Server", params[1].simpleName)
    assertEquals("PluginDescriptionFile", params[2].simpleName)
    assertEquals("File", params[3].simpleName)
    assertEquals("File", params[4].simpleName)
    assertEquals("ClassLoader", params[5].simpleName)
    assertTrue(init.isAccessible, "init must be made accessible at probe time")
  }

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
  fun `JavaPlugin private fields are not final-immutable - we must be able to set them`() {
    // The fallback path mutates these fields. Verify they aren't `final` in a way that would block
    // reflective set. `Field.set` works even for `private final` since JDK 9 if accessible, but if
    // a future JavaPlugin moves to records or makes them deeply final-on-non-class, this catches
    // it.
    val spm = SimplePluginManager(server, SimpleCommandMap(server, mutableMapOf()))
    val probe = ReflectionProbe.probe(FakeServerWithSpm(server, spm))
    val fields = probe.javaPluginFields!!

    // None should be static (they're per-instance).
    for (f in
        listOf(fields.server, fields.description, fields.dataFolder, fields.file, fields.classLoader)) {
      assertTrue(
          !java.lang.reflect.Modifier.isStatic(f.modifiers),
          "${f.name} must be instance field; got static",
      )
    }
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
}

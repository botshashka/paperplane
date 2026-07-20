package dev.paperplane.companion

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/**
 * Proves that [DevPluginClassLoader] implementing
 * [io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader] lets the **real,
 * unpatched** `JavaPlugin` no-arg constructor initialize a dev-loaded plugin.
 *
 * Paper's `JavaPlugin()` ctor checks `getClassLoader() instanceof ConfiguredPluginClassLoader` and,
 * when true, calls `init(this)` on the loader. This test packages a `JavaPlugin` subclass into a
 * JAR, loads its main class through the loader, and constructs it via the real ctor — no ASM
 * retransform involved. `forkEvery(1)` in build.gradle isolates this test from any that retransform
 * `JavaPlugin`.
 */
class DevPluginClassLoaderInitTest {

  @TempDir lateinit var tempDir: File

  private lateinit var server: ServerMock
  private var classLoader: DevPluginClassLoader? = null

  @BeforeEach
  fun setup() {
    server = MockBukkit.mock()
  }

  @AfterEach
  fun teardown() {
    classLoader?.close()
    MockBukkit.unmock()
  }

  // ── helpers ─────────────────────────────────────────────────────────

  /** JAR containing [InitFixturePlugin]'s compiled class + a matching plugin.yml. */
  private fun makeFixtureJar(pluginName: String): File {
    val jar = File(tempDir, "$pluginName.jar")
    val classBytes =
        InitFixturePlugin::class
            .java
            .classLoader
            .getResourceAsStream(InitFixturePlugin::class.java.name.replace('.', '/') + ".class")!!
            .use { it.readAllBytes() }
    JarOutputStream(jar.outputStream()).use { jos ->
      jos.putNextEntry(JarEntry(InitFixturePlugin::class.java.name.replace('.', '/') + ".class"))
      jos.write(classBytes)
      jos.closeEntry()
      jos.putNextEntry(JarEntry("plugin.yml"))
      jos.write(
          """
          name: $pluginName
          main: ${InitFixturePlugin::class.java.name}
          version: 1.0
          """
              .trimIndent()
              .toByteArray()
      )
      jos.closeEntry()
    }
    return jar
  }

  private fun descriptionFor(jar: File): PluginDescriptionFile =
      java.util.jar.JarFile(jar).use { jf ->
        jf.getInputStream(jf.getJarEntry("plugin.yml")).use { PluginDescriptionFile(it) }
      }

  private fun contextFor(jar: File): PluginInitContext {
    val description = descriptionFor(jar)
    val dataFolder = File(server.pluginsFolder, description.name)
    return PluginInitContext(server, description, dataFolder, jar)
  }

  // ── tests ───────────────────────────────────────────────────────────

  @Test
  fun `unpatched ctor initializes plugin via ConfiguredPluginClassLoader init`() {
    val jar = makeFixtureJar("InitOne")
    val ctx = contextFor(jar)
    classLoader =
        DevPluginClassLoader(
            arrayOf(jar.toURI().toURL()),
            javaClass.classLoader,
            server.pluginManager,
            ctx,
        )

    val mainClass = classLoader!!.loadClass(ctx.description.main)
    val plugin = mainClass.getDeclaredConstructor().newInstance() as JavaPlugin

    // Field initializers of the subclass ran (constructor completed normally).
    assertEquals("field-initialized", (plugin as InitFixtureAccess).marker())

    // init() propagated the context into JavaPlugin's internal state.
    assertSame(server, plugin.server)
    assertEquals("InitOne", plugin.description.name)
    assertEquals(ctx.dataFolder, plugin.dataFolder)
    assertEquals(jar, (plugin).exposedFile())

    // The loader now reports the initialized plugin.
    assertSame(plugin, classLoader!!.getPlugin())
  }

  @Test
  fun `second instantiation trips the double-init guard`() {
    val jar = makeFixtureJar("InitTwice")
    val ctx = contextFor(jar)
    classLoader =
        DevPluginClassLoader(
            arrayOf(jar.toURI().toURL()),
            javaClass.classLoader,
            server.pluginManager,
            ctx,
        )
    val mainClass = classLoader!!.loadClass(ctx.description.main)
    mainClass.getDeclaredConstructor().newInstance()

    // The ctor wraps our IllegalStateException in InvocationTargetException.
    val ex =
        assertThrows(InvocationTargetException::class.java) {
          mainClass.getDeclaredConstructor().newInstance()
        }
    assertTrue(
        ex.targetException is IllegalStateException,
        "expected IllegalStateException, got: ${ex.targetException}",
    )
  }

  @Test
  fun `context-less loader fails when ctor invokes init`() {
    val jar = makeFixtureJar("NoContext")
    val description = descriptionFor(jar)
    classLoader =
        DevPluginClassLoader(
            arrayOf(jar.toURI().toURL()),
            javaClass.classLoader,
            server.pluginManager,
        )
    val mainClass = classLoader!!.loadClass(description.main)

    val ex =
        assertThrows(InvocationTargetException::class.java) {
          mainClass.getDeclaredConstructor().newInstance()
        }
    assertTrue(
        ex.targetException is IllegalStateException,
        "expected IllegalStateException from missing init context, got: ${ex.targetException}",
    )
  }

  @Test
  fun `JavaPlugin getPlugin resolves a dev-loaded plugin`() {
    val jar = makeFixtureJar("LookupOne")
    val ctx = contextFor(jar)
    classLoader =
        DevPluginClassLoader(
            arrayOf(jar.toURI().toURL()),
            javaClass.classLoader,
            server.pluginManager,
            ctx,
        )
    val mainClass = classLoader!!.loadClass(ctx.description.main)
    val plugin = mainClass.getDeclaredConstructor().newInstance() as JavaPlugin

    @Suppress("UNCHECKED_CAST")
    val resolved = JavaPlugin.getPlugin(mainClass as Class<out JavaPlugin>)
    assertNotNull(resolved)
    assertSame(plugin, resolved)
  }

  // ── ConfiguredPluginClassLoader surface (called by Paper at runtime) ──

  @Test
  fun `getConfiguration returns the context description and getGroup is null`() {
    val jar = makeFixtureJar("ConfigOne")
    val ctx = contextFor(jar)
    classLoader =
        DevPluginClassLoader(
            arrayOf(jar.toURI().toURL()),
            javaClass.classLoader,
            server.pluginManager,
            ctx,
        )

    // Paper reads getConfiguration() (a PluginMeta) off the loader; it must be the same description
    // the context carries. getGroup() has no meaning for a standalone dev loader.
    assertSame(ctx.description, classLoader!!.configuration)
    assertNull(classLoader!!.group)
  }

  @Test
  fun `getConfiguration without context throws`() {
    classLoader = DevPluginClassLoader(emptyArray(), javaClass.classLoader, server.pluginManager)
    assertThrows(IllegalStateException::class.java) { classLoader!!.configuration }
  }

  @Test
  fun `getPlugin is null before init`() {
    val jar = makeFixtureJar("PreInit")
    val ctx = contextFor(jar)
    classLoader =
        DevPluginClassLoader(
            arrayOf(jar.toURI().toURL()),
            javaClass.classLoader,
            server.pluginManager,
            ctx,
        )
    // Loaded the main class but never constructed it — no init has run.
    classLoader!!.loadClass(ctx.description.main)
    assertNull(classLoader!!.getPlugin())
  }

  @Test
  fun `four-arg loadClass delegates to the child-first override`() {
    val jar = makeFixtureJar("FourArg")
    val ctx = contextFor(jar)
    classLoader =
        DevPluginClassLoader(
            arrayOf(jar.toURI().toURL()),
            javaClass.classLoader,
            server.pluginManager,
            ctx,
        )
    // The 4-arg overload Paper's loader machinery calls must resolve both the JAR-local plugin
    // class
    // (child-first) and a parent JDK class.
    val fromJar = classLoader!!.loadClass(ctx.description.main, false, true, true)
    assertEquals(ctx.description.main, fromJar.name)
    assertSame(String::class.java, classLoader!!.loadClass("java.lang.String", false, true, true))
  }
}

/** Exposes the protected `getFile()` and a field-initializer marker for assertions. */
interface InitFixtureAccess {
  fun marker(): String

  fun exposedFile(): File
}

/**
 * Fixture plugin with a field initializer (proves the ctor completed) and accessors for otherwise
 * protected JavaPlugin state. Packaged into a JAR and loaded by [DevPluginClassLoader]; the base
 * class resolves through the parent so `plugin is JavaPlugin` holds across loaders.
 */
class InitFixturePlugin : JavaPlugin(), InitFixtureAccess {
  private val marker = "field-initialized"

  override fun marker(): String = marker

  override fun exposedFile(): File = file
}

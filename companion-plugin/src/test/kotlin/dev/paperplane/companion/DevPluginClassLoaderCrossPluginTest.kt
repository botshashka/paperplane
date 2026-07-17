package dev.paperplane.companion

import java.lang.reflect.Proxy
import java.time.Duration
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test

/**
 * Guards the cross-plugin fallback in [DevPluginClassLoader.loadClass] against unbounded recursion.
 *
 * Two dev loaders that each list the other's plugin form an A→B→A cycle when asked for a class
 * neither can provide. Before the reentrancy guard this overflowed the stack; it must now unwind to
 * a plain [ClassNotFoundException].
 */
class DevPluginClassLoaderCrossPluginTest {

  /** A [PluginManager] whose only meaningful method is `getPlugins()`. */
  private fun pmReturning(plugins: () -> Array<Plugin>): PluginManager =
      Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PluginManager::class.java)) {
          _,
          method,
          _ ->
        if (method.name == "getPlugins") plugins() else null
      } as PluginManager

  /**
   * A no-op [Plugin] whose runtime class is defined by [loader] (so its classLoader is [loader]).
   */
  private fun pluginLoadedBy(loader: ClassLoader): Plugin =
      Proxy.newProxyInstance(loader, arrayOf(Plugin::class.java)) { _, _, _ -> null } as Plugin

  @Test
  fun `cross-plugin fallback for an absent class terminates instead of recursing forever`() {
    val parent = javaClass.classLoader
    lateinit var a: DevPluginClassLoader
    lateinit var b: DevPluginClassLoader
    // Mutually-referential: A lists a plugin loaded by B, B lists a plugin loaded by A.
    val pmA = pmReturning { arrayOf(pluginLoadedBy(b)) }
    val pmB = pmReturning { arrayOf(pluginLoadedBy(a)) }
    a = DevPluginClassLoader(emptyArray(), parent, pmA)
    b = DevPluginClassLoader(emptyArray(), parent, pmB)

    // Without the guard, A→B→A→… overflows the stack. With it, the search unwinds cleanly.
    assertTimeoutPreemptively(Duration.ofSeconds(5)) {
      assertThrows(ClassNotFoundException::class.java) {
        a.loadClass("com.example.DefinitelyAbsentClass")
      }
    }
  }
}

package dev.paperplane.companion

import io.papermc.paper.plugin.configuration.PluginMeta
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import org.bukkit.Server
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.java.JavaPlugin

/** Everything `JavaPlugin.init` needs, known before the plugin class is instantiated. */
class PluginInitContext(
    val server: Server,
    val description: PluginDescriptionFile,
    val dataFolder: File,
    val pluginFile: File,
)

/**
 * A classloader for dev-mode plugin loading from build output directories.
 *
 * Unlike Paper's PluginClassLoader (which is internal and version-specific), this extends plain
 * URLClassLoader and replicates cross-plugin class visibility by falling back to other plugins'
 * classloaders. This allows plugins with `depend` or `softdepend` relationships to work correctly
 * in HMR mode.
 *
 * It also implements [ConfiguredPluginClassLoader]. Paper's current `JavaPlugin` no-arg constructor
 * checks `getClassLoader() instanceof ConfiguredPluginClassLoader` and, when true, calls
 * `init(this)` on the loader — so implementing that interface lets the **unpatched** constructor
 * initialize a dev-loaded plugin, replacing the old ASM bytecode surgery with a
 * compile-time-checked integration. [init] wires the plugin's internal state via the public 7-arg
 * `JavaPlugin.init`.
 *
 * Uses **child-first** delegation: own URLs are checked before the parent. This is critical because
 * Paper's parent classloader has shared visibility across all plugins — without child-first, the
 * OLD plugin class (from the original PluginClassLoader) would be found instead of the NEW class
 * from our build output directories.
 *
 * Delegation order:
 * 1. Already loaded classes (JVM cache)
 * 2. Own URLs — child-first (plugin classes from build dirs + dependency JARs)
 * 3. Parent classloader (Paper API, JDK, Adventure)
 * 4. Other plugins' classloaders (cross-plugin visibility)
 */
class DevPluginClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    private val pluginManager: PluginManager,
    private val initContext: PluginInitContext? = null,
) : URLClassLoader(urls, parent), ConfiguredPluginClassLoader {

  @Volatile private var initializedPlugin: JavaPlugin? = null

  // Class names currently being resolved through the cross-plugin fallback on this thread. Guards
  // against a pair of dev loaders that list each other's plugins recursing forever on a class
  // neither can provide (A→B→A→…): a re-entry for a name already in flight short-circuits to
  // "unresolved" so the search unwinds to a clean ClassNotFoundException instead of a stack
  // overflow.
  private val crossPluginInProgress = ThreadLocal.withInitial { HashSet<String>() }

  /**
   * Defines a brand-new class from bytes received over the socket — the instant tier's new-class
   * path for when the build output is not visible on this loader's URLs (no shared filesystem with
   * the CLI, e.g. a containerized server). Throws [LinkageError] if the name is already defined;
   * callers treat that as a refusal.
   */
  fun defineNew(fqcn: String, bytes: ByteArray): Class<*> =
      synchronized(getClassLoadingLock(fqcn)) { defineClass(fqcn, bytes, 0, bytes.size) }

  override fun loadClass(name: String, resolve: Boolean): Class<*> {
    synchronized(getClassLoadingLock(name)) {
      // 1. Check already loaded
      findLoadedClass(name)?.let {
        return it
      }

      // 2. Try our own URLs first (child-first — ensures new plugin classes
      //    take priority over stale classes in Paper's shared classloader pool)
      try {
        val c = findClass(name)
        if (resolve) resolveClass(c)
        return c
      } catch (_: ClassNotFoundException) {}

      // 3. Try parent (Paper API, JDK, Adventure)
      try {
        return parent.loadClass(name)
      } catch (_: ClassNotFoundException) {}

      // 4. Try other plugins' classloaders (cross-plugin visibility)
      loadFromOtherPlugins(name)?.let {
        return it
      }

      throw ClassNotFoundException(name)
    }
  }

  /**
   * Cross-plugin visibility (step 4): try every other plugin's classloader in turn. Reentrancy-
   * guarded via [crossPluginInProgress] so mutually-referential dev loaders can't recurse
   * indefinitely — the second entry for the same class on this thread returns null (unresolved).
   */
  private fun loadFromOtherPlugins(name: String): Class<*>? {
    val inProgress = crossPluginInProgress.get()
    if (!inProgress.add(name)) return null
    try {
      for (plugin in pluginManager.plugins) {
        val cl = plugin.javaClass.classLoader
        if (cl === this) continue
        try {
          return cl.loadClass(name)
        } catch (_: ClassNotFoundException) {}
      }
      return null
    } finally {
      inProgress.remove(name)
    }
  }

  // ── ConfiguredPluginClassLoader ──────────────────────────────────────
  // (Closeable is already satisfied by URLClassLoader.)

  override fun getConfiguration(): PluginMeta =
      initContext?.description ?: error("DevPluginClassLoader created without init context")

  override fun loadClass(
      name: String,
      resolve: Boolean,
      checkGlobal: Boolean,
      checkLibraries: Boolean,
  ): Class<*> = loadClass(name, resolve) // delegate to the existing child-first override

  override fun init(plugin: JavaPlugin) {
    val ctx = initContext ?: error("DevPluginClassLoader created without init context")
    check(initializedPlugin == null) { "Plugin already initialized for this classloader" }
    val logger =
        try {
          com.destroystokyo.paper.utils.PaperPluginLogger.getLogger(ctx.description)
        } catch (_: LinkageError) {
          java.util.logging.Logger.getLogger(ctx.description.name)
        }
    plugin.init(
        ctx.server,
        ctx.description,
        ctx.dataFolder,
        ctx.pluginFile,
        this,
        ctx.description,
        logger,
    )
    initializedPlugin = plugin
  }

  override fun getPlugin(): JavaPlugin? = initializedPlugin

  override fun getGroup(): PluginClassLoaderGroup? = null
}

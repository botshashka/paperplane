package dev.paperplane.companion.host

import dev.paperplane.companion.DevPluginClassLoader
import dev.paperplane.companion.JavaPluginPatcher
import java.io.File
import java.io.InputStream
import java.lang.instrument.Instrumentation
import java.lang.ref.WeakReference
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.util.logging.Logger
import org.bukkit.Server
import org.bukkit.event.HandlerList
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.java.JavaPlugin

/**
 * The host. Owns the inner plugin's lifecycle from start to finish.
 *
 * Paper auto-loads only the companion. The user's plugin lives in `.paperplane/staged/`, never in
 * `plugins/`. When the CLI signals a load via `load-request.json`, the host:
 *
 * 1. Parses and validates `plugin.yml` (rejects unsupported shapes — `load: STARTUP` etc).
 * 2. Builds a child [DevPluginClassLoader] with cross-plugin visibility.
 * 3. Instantiates the inner plugin (no-arg ctor; JavaPluginPatcher already removed the
 *    PluginClassLoader type check).
 * 4. Initializes JavaPlugin via [ReflectionProbe] (init method first; fields fallback).
 * 5. Registers the inner plugin in `SimplePluginManager.lookupNames` so cross-plugin
 *    `getPlugin(name)` works.
 * 6. Applies commands + permissions diffs via the public-API registrars.
 * 7. Calls `pluginManager.enablePlugin(inner)` — fires `PluginEnableEvent` correctly.
 *
 * Reload is teardown + load. Teardown is entirely public API except for the symmetric
 * `lookupNames` removal.
 */
open class InnerPluginHost(
    private val server: Server,
    private val parentClassLoader: ClassLoader,
    private val probe: ReflectionProbe,
    private val logger: Logger,
) {

  companion object {
    private const val THREAD_JOIN_TIMEOUT_MS = 2000L
    private const val MAX_THREAD_STACK_FRAMES = 5
    private const val MAX_CONSECUTIVE_LEAKS = 3

    /**
     * Returns true when [inst]'s view of loaded classes shows any NMS or CraftBukkit class defined
     * by [classLoader]. Pulled out as a pure helper so tests can drive it with a fake
     * [Instrumentation] without booting Paper.
     */
    internal fun containsNmsClasses(inst: Instrumentation, classLoader: ClassLoader): Boolean =
        inst.allLoadedClasses.any { c ->
          c.classLoader === classLoader &&
              (c.name.startsWith("net.minecraft.") ||
                  c.name.startsWith("org.bukkit.craftbukkit."))
        }
  }

  private val helpMapWriter = HelpMapWriter(server.helpMap, probe.helpTopics)
  private val commandRegistrar = CommandRegistrar(server, helpMapWriter, logger)
  private val permissionRegistrar = PermissionRegistrar(server)
  private val leakedClassLoaders = mutableListOf<WeakReference<ClassLoader>>()
  private var consecutiveLeaks = 0

  private var active: Active? = null

  /** True when too many classloader leaks accumulated; CLI should fall back to blue/green. */
  open val shouldForceBlueGreen: Boolean
    get() = consecutiveLeaks >= MAX_CONSECUTIVE_LEAKS

  /** The currently-loaded inner plugin, or `null` before first load / after shutdown. */
  open fun current(): JavaPlugin? = active?.plugin

  /** True when an inner plugin is currently loaded. */
  open fun isLoaded(): Boolean = active != null

  /**
   * Top-level entry point: handle a [LoadRequest]. First request loads; subsequent requests
   * reload. Tear-down on failure does NOT happen automatically — the previous plugin remains
   * loaded if reload fails (matches the existing rollback semantics).
   */
  open fun handleRequest(request: HostLoadRequest): HostLoadResult {
    val start = System.currentTimeMillis()
    return try {
      if (active == null) {
        loadFresh(request)
      } else {
        reload(request)
      }.let { result ->
        val ms = System.currentTimeMillis() - start
        when (result) {
          is HostLoadResult.Ok -> HostLoadResult.Ok(result.pluginName, ms)
          is HostLoadResult.Failed -> HostLoadResult.Failed(result.message, ms)
        }
      }
    } catch (
        @Suppress("TooGenericExceptionCaught") // Last-resort safety net for the whole pipeline.
        e: Exception) {
      logger.severe("Host load failed: ${e.message}")
      logger.severe(e.stackTraceToString())
      HostLoadResult.Failed(e.message ?: "unknown error", System.currentTimeMillis() - start)
    }
  }

  /** Tear down any currently-loaded inner. Idempotent. Called by companion `onDisable`. */
  open fun shutdown() {
    val a = active ?: return
    teardown(a)
    active = null
  }

  // ── load ────────────────────────────────────────────────────────────

  private fun loadFresh(request: HostLoadRequest): HostLoadResult {
    val description = readDescription(request) ?: return HostLoadResult.Failed("plugin.yml not found", 0)
    when (val v = PluginYmlValidator.validate(description, server, logger)) {
      is PluginYmlValidator.Result.Reject -> return HostLoadResult.Failed(v.message, 0)
      PluginYmlValidator.Result.Ok -> {}
    }

    val (plugin, classLoader) = loadPlugin(request, description)
    initializeAndRegister(plugin, classLoader, description)
    active = Active(plugin, classLoader, description.name)
    return HostLoadResult.Ok(description.name, 0)
  }

  // ── reload ──────────────────────────────────────────────────────────

  private fun reload(request: HostLoadRequest): HostLoadResult {
    val a = active!!

    if (shouldForceBlueGreen) {
      return HostLoadResult.Failed(
          "Hot-reload disabled: $consecutiveLeaks consecutive classloader leaks", 0)
    }

    if (usesNmsClasses(a.plugin)) {
      return HostLoadResult.Failed(
          "Plugin '${a.name}' uses NMS/CraftBukkit classes — skipping hot-reload", 0)
    }

    val description = readDescription(request) ?: return HostLoadResult.Failed("plugin.yml not found", 0)
    when (val v = PluginYmlValidator.validate(description, server, logger)) {
      is PluginYmlValidator.Result.Reject -> return HostLoadResult.Failed(v.message, 0)
      PluginYmlValidator.Result.Ok -> {}
    }

    teardown(a)
    leakedClassLoaders.add(WeakReference(a.classLoader))

    val (plugin, classLoader) = loadPlugin(request, description)
    initializeAndRegister(plugin, classLoader, description)
    active = Active(plugin, classLoader, description.name)

    if (!plugin.isEnabled) {
      return HostLoadResult.Failed("Plugin '${description.name}' is not enabled after reload", 0)
    }
    checkForLeaks()
    return HostLoadResult.Ok(description.name, 0)
  }

  // ── teardown ────────────────────────────────────────────────────────

  private fun teardown(a: Active) {
    server.pluginManager.disablePlugin(a.plugin)
    HandlerList.unregisterAll(a.plugin)
    server.scheduler.cancelTasks(a.plugin)
    server.servicesManager.unregisterAll(a.plugin)
    server.messenger.unregisterIncomingPluginChannel(a.plugin)
    server.messenger.unregisterOutgoingPluginChannel(a.plugin)
    commandRegistrar.clear()
    permissionRegistrar.clear()
    interruptPluginThreads(a.classLoader)
    removeFromLookupNames(a.name)
    try {
      a.classLoader.close()
    } catch (
        @Suppress("TooGenericExceptionCaught") // close() may throw on broken streams; logged + swallowed
        e: Exception) {
      logger.warning("Failed to close inner classloader: ${e.message}")
    }
  }

  // ── load helpers ────────────────────────────────────────────────────

  private fun loadPlugin(
      request: HostLoadRequest,
      description: PluginDescriptionFile,
  ): Pair<JavaPlugin, DevPluginClassLoader> {
    val urls = buildClassLoaderUrls(request)
    val classLoader = DevPluginClassLoader(urls, parentClassLoader, server.pluginManager)

    val mainClass = classLoader.loadClass(description.main)
    val plugin = mainClass.getDeclaredConstructor().newInstance() as JavaPlugin
    return plugin to classLoader
  }

  private fun initializeAndRegister(
      plugin: JavaPlugin,
      classLoader: DevPluginClassLoader,
      description: PluginDescriptionFile,
  ) {
    val dataFolder = File(server.pluginsFolder, description.name)
    initializePlugin(plugin, description, dataFolder, classLoader)
    addToLookupNames(description.name, plugin)
    permissionRegistrar.apply(description)
    commandRegistrar.apply(plugin, description)
    server.pluginManager.enablePlugin(plugin)
  }

  /**
   * Sets the JavaPlugin's internal state. Tries the package-private `init(...)` method first;
   * falls back to direct field injection only if init was not resolvable at probe time.
   */
  private fun initializePlugin(
      plugin: JavaPlugin,
      description: PluginDescriptionFile,
      dataFolder: File,
      classLoader: ClassLoader,
  ) {
    val initMethod = probe.javaPluginInit
    if (initMethod != null) {
      val loader = makeJavaPluginLoader()
      initMethod.invoke(plugin, loader, server, description, dataFolder, dataFolder, classLoader)
      return
    }

    val fields = probe.javaPluginFields ?: error("ReflectionProbe accepted with neither init nor fields")
    fields.server.set(plugin, server)
    fields.description.set(plugin, description)
    fields.dataFolder.set(plugin, dataFolder)
    fields.file.set(plugin, dataFolder)
    fields.classLoader.set(plugin, classLoader)
  }

  @Suppress("DEPRECATION") // JavaPluginLoader is deprecated but the only legitimate constructor for init().
  private fun makeJavaPluginLoader(): org.bukkit.plugin.java.JavaPluginLoader =
      org.bukkit.plugin.java.JavaPluginLoader(server)

  private fun addToLookupNames(name: String, plugin: JavaPlugin) {
    val spm = ReflectionProbe.unwrapSpm(server.pluginManager) ?: return
    @Suppress("UNCHECKED_CAST")
    val map = probe.spmLookupNamesField.get(spm) as MutableMap<String, org.bukkit.plugin.Plugin>
    map[name.lowercase()] = plugin
  }

  private fun removeFromLookupNames(name: String) {
    val spm = ReflectionProbe.unwrapSpm(server.pluginManager) ?: return
    @Suppress("UNCHECKED_CAST")
    val map = probe.spmLookupNamesField.get(spm) as MutableMap<String, org.bukkit.plugin.Plugin>
    map.remove(name.lowercase())
  }

  // ── plugin.yml resolution ───────────────────────────────────────────

  private fun readDescription(request: HostLoadRequest): PluginDescriptionFile? {
    if (request.resourcesDir.isNotEmpty()) {
      val ymlFile = File(request.resourcesDir, "plugin.yml")
      if (ymlFile.exists()) return PluginDescriptionFile(ymlFile.inputStream())
    }
    val jar = File(request.jarPath)
    if (!jar.exists()) return null
    return JarFile(jar).use { jf ->
      val entry = jf.getJarEntry("plugin.yml") ?: return null
      jf.getInputStream(entry).use(::readDescriptionFromStream)
    }
  }

  private fun readDescriptionFromStream(stream: InputStream): PluginDescriptionFile =
      PluginDescriptionFile(stream)

  private fun buildClassLoaderUrls(request: HostLoadRequest): Array<java.net.URL> {
    val urls = mutableListOf<java.net.URL>()
    for (dir in request.classesDirs) {
      if (dir.isNotEmpty()) urls.add(File(dir).toURI().toURL())
    }
    if (request.resourcesDir.isNotEmpty()) {
      urls.add(File(request.resourcesDir).toURI().toURL())
    }
    for (path in request.runtimeClasspath) {
      if (path.isNotEmpty()) urls.add(File(path).toURI().toURL())
    }
    if (urls.isEmpty()) {
      // JAR-only mode — directory load wasn't requested.
      urls.add(File(request.jarPath).toURI().toURL())
    }
    return urls.toTypedArray()
  }

  // ── safety nets ─────────────────────────────────────────────────────

  protected open fun usesNmsClasses(plugin: JavaPlugin): Boolean {
    val inst = JavaPluginPatcher.instrumentation() ?: return false
    return containsNmsClasses(inst, plugin.javaClass.classLoader)
  }

  private fun interruptPluginThreads(classLoader: ClassLoader) {
    val threads =
        Thread.getAllStackTraces().keys.filter {
          it.contextClassLoader == classLoader && it != Thread.currentThread()
        }
    for (t in threads) t.interrupt()
    if (threads.isNotEmpty()) {
      logger.warning("Interrupted ${threads.size} orphan thread(s) from old plugin")
    }
    for (t in threads) {
      t.join(THREAD_JOIN_TIMEOUT_MS)
      if (t.isAlive) {
        val stack =
            t.stackTrace.take(MAX_THREAD_STACK_FRAMES).joinToString("\n    ") { it.toString() }
        logger.warning("Thread '${t.name}' did not stop after interrupt:\n    $stack")
      }
    }
  }

  private fun checkForLeaks() {
    if (leakedClassLoaders.size <= 1) return
    System.gc()
    Thread.sleep(100L)
    val stillLeaking = leakedClassLoaders.dropLast(1).count { it.get() != null }
    leakedClassLoaders.removeAll { it != leakedClassLoaders.last() && it.get() == null }
    if (stillLeaking > 0) {
      System.gc()
      Thread.sleep(100L)
      val confirmed = leakedClassLoaders.dropLast(1).count { it.get() != null }
      leakedClassLoaders.removeAll { it != leakedClassLoaders.last() && it.get() == null }
      if (confirmed > 0) {
        consecutiveLeaks++
        logger.warning("$confirmed classloader(s) leaked (#$consecutiveLeaks)")
      } else {
        consecutiveLeaks = 0
      }
    } else {
      consecutiveLeaks = 0
    }
  }

  private data class Active(
      val plugin: JavaPlugin,
      val classLoader: DevPluginClassLoader,
      val name: String,
  )
}

/** Mirror of CLI's `LoadRequest` — same JSON shape, no cross-module dependency. */
data class HostLoadRequest(
    val requestId: String = "",
    val jarPath: String = "",
    val pluginName: String = "",
    val classesDirs: List<String> = emptyList(),
    val resourcesDir: String = "",
    val runtimeClasspath: List<String> = emptyList(),
    val changedClasses: List<String> = emptyList(),
)

sealed class HostLoadResult {
  abstract val durationMs: Long

  data class Ok(val pluginName: String, override val durationMs: Long) : HostLoadResult()

  data class Failed(val message: String, override val durationMs: Long) : HostLoadResult()
}

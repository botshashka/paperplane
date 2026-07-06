package dev.paperplane.companion.host

import dev.paperplane.companion.AgentAccess
import dev.paperplane.companion.DevPluginClassLoader
import dev.paperplane.companion.PluginInitContext
import java.io.File
import java.io.InputStream
import java.lang.instrument.Instrumentation
import java.lang.ref.WeakReference
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
 * 2. Builds a child [DevPluginClassLoader] (a
 *    [io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader]) with cross-plugin
 *    visibility, carrying a [PluginInitContext].
 * 3. Instantiates the inner plugin (no-arg ctor). Paper's `JavaPlugin` ctor sees the loader is a
 *    `ConfiguredPluginClassLoader` and calls back into `init(plugin)`, wiring the plugin's state.
 * 4. Registers the inner plugin in `SimplePluginManager.lookupNames` so cross-plugin
 *    `getPlugin(name)` works.
 * 5. Applies commands + permissions diffs via the public-API registrars.
 * 6. Calls `pluginManager.enablePlugin(inner)` — fires `PluginEnableEvent` correctly.
 *
 * Reload is teardown + load. Teardown is entirely public API except for the symmetric `lookupNames`
 * removal.
 */
open class InnerPluginHost(
    private val server: Server,
    private val parentClassLoader: ClassLoader,
    private val probe: ReflectionProbe,
    private val logger: Logger,
    private val hostPlugin: org.bukkit.plugin.Plugin? = null,
    /**
     * How much leak diagnostics to emit. Gates output only — leak counting and attribution always
     * run so the load report and the auto-restart action work in every mode. Read by
     * [BuildStatusBar] to decide whether to schedule the deferred verbose + heap dumps. See
     * [LeakDiagnosticsMode].
     */
    val leakDiagnostics: LeakDiagnosticsMode = LeakDiagnosticsMode.SUMMARY,
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
              (c.name.startsWith("net.minecraft.") || c.name.startsWith("org.bukkit.craftbukkit."))
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
   * Top-level entry point: handle a [LoadRequest]. First request loads; subsequent requests reload.
   * Tear-down on failure does NOT happen automatically — the previous plugin remains loaded if
   * reload fails (matches the existing rollback semantics).
   */
  open fun handleRequest(request: HostLoadRequest): HostLoadResult {
    val start = System.currentTimeMillis()
    return try {
      if (active == null) {
            loadFresh(request)
          } else {
            reload(request)
          }
          .withDuration(System.currentTimeMillis() - start)
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
    val description =
        readDescription(request) ?: return HostLoadResult.Failed("plugin.yml not found", 0)
    when (val v = PluginYmlValidator.validate(description, server, logger)) {
      is PluginYmlValidator.Result.Reject -> return HostLoadResult.Failed(v.message, 0)
      PluginYmlValidator.Result.Ok -> {}
    }

    val (plugin, classLoader) = loadPlugin(request, description)
    initializeAndRegister(plugin, description)
    active = Active(plugin, classLoader, description.name)
    return HostLoadResult.Ok(description.name, 0)
  }

  // ── reload ──────────────────────────────────────────────────────────

  private fun reload(request: HostLoadRequest): HostLoadResult {
    val a = active!!

    if (shouldForceBlueGreen) {
      return HostLoadResult.Failed(
          "Hot-reload disabled: $consecutiveLeaks consecutive classloader leaks",
          0,
      )
    }

    if (usesNmsClasses(a.plugin)) {
      return HostLoadResult.Failed(
          "Plugin '${a.name}' uses NMS/CraftBukkit classes — skipping hot-reload",
          0,
      )
    }

    val description =
        readDescription(request) ?: return HostLoadResult.Failed("plugin.yml not found", 0)
    when (val v = PluginYmlValidator.validate(description, server, logger)) {
      is PluginYmlValidator.Result.Reject -> return HostLoadResult.Failed(v.message, 0)
      PluginYmlValidator.Result.Ok -> {}
    }

    teardown(a)
    leakedClassLoaders.add(WeakReference(a.classLoader))

    val (plugin, classLoader) = loadPlugin(request, description)
    initializeAndRegister(plugin, description)
    active = Active(plugin, classLoader, description.name)

    if (!plugin.isEnabled) {
      return HostLoadResult.Failed("Plugin '${description.name}' is not enabled after reload", 0)
    }
    val leaks = checkForLeaks()
    return HostLoadResult.Ok(description.name, 0, leaks = leaks)
  }

  // ── teardown ────────────────────────────────────────────────────────

  private fun teardown(a: Active) {
    server.pluginManager.disablePlugin(a.plugin)
    HandlerList.unregisterAll(a.plugin)
    server.scheduler.cancelTasks(a.plugin)
    // CraftScheduler.cancelTasks enqueues a sentinel task (anonymous CraftScheduler$3) that
    // captures `plugin` via val$plugin. After parsePending() consumes it, CraftScheduler.head is
    // reassigned to point at that sentinel — pinning the inner plugin and its classloader for the
    // entire idle gap until something else is enqueued. Posting a host-owned no-op forces head to
    // advance to a task that captures only the long-lived companion, releasing the inner plugin.
    hostPlugin?.let { server.scheduler.runTask(it, Runnable {}) }
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
        @Suppress(
            "TooGenericExceptionCaught"
        ) // close() may throw on broken streams; logged + swallowed
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
    val dataFolder = File(server.pluginsFolder, description.name)
    val context = PluginInitContext(server, description, dataFolder, File(request.jarPath))
    val classLoader = DevPluginClassLoader(urls, parentClassLoader, server.pluginManager, context)

    val mainClass = classLoader.loadClass(description.main)
    val plugin = mainClass.getDeclaredConstructor().newInstance() as JavaPlugin
    // Transition-window fallback: while the ASM patcher still exists (removed in Commit 3), the
    // patched ctor skips CPCL init. Also guards hypothetical Paper builds where init never fires.
    if (classLoader.getPlugin() == null) classLoader.init(plugin)
    check(classLoader.getPlugin() === plugin) {
      "Inner plugin was not initialized by its classloader"
    }
    return plugin to classLoader
  }

  private fun initializeAndRegister(
      plugin: JavaPlugin,
      description: PluginDescriptionFile,
  ) {
    addToLookupNames(description.name, plugin)
    if (server.pluginManager.getPlugin(description.name) != null) {
      logger.info("Cross-plugin lookup verified for '${description.name}'")
    } else {
      logger.warning(
          "Cross-plugin lookup FAILED for '${description.name}' — getPlugin(name) returns null"
      )
    }
    permissionRegistrar.apply(description)
    commandRegistrar.apply(plugin, description)
    server.pluginManager.enablePlugin(plugin)
  }

  private fun addToLookupNames(name: String, plugin: JavaPlugin) {
    val key = name.lowercase()
    // Paper's modern manager consults its OWN map for getPlugin(name) — the SPM write below is
    // invisible to it. Write both: the Paper map for lookups, SPM's for legacy reflection compat.
    probe.paperLookupNames?.put(key, plugin)
    val spm = ReflectionProbe.unwrapSpm(server.pluginManager) ?: return
    @Suppress("UNCHECKED_CAST")
    val map = probe.spmLookupNamesField.get(spm) as MutableMap<String, org.bukkit.plugin.Plugin>
    map[key] = plugin
  }

  private fun removeFromLookupNames(name: String) {
    val key = name.lowercase()
    probe.paperLookupNames?.remove(key)
    val spm = ReflectionProbe.unwrapSpm(server.pluginManager) ?: return
    @Suppress("UNCHECKED_CAST")
    val map = probe.spmLookupNamesField.get(spm) as MutableMap<String, org.bukkit.plugin.Plugin>
    map.remove(key)
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
    val inst = AgentAccess.instrumentation() ?: return false
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

  /**
   * Runs the cheap, synchronous leak scan at the end of a successful reload: a double-GC survivor
   * count plus a thread/scheduler attribution scan. Returns a [LeakSummary] when a leak is
   * confirmed (so it rides out on the load report's JSON), or `null` on a clean reload. The
   * heavyweight, heap-walking diagnostics ([dumpVerboseDiagnostics]) and the heap snapshot
   * ([tryDumpHeap]) are NOT run here — [BuildStatusBar] defers them until after the report is on
   * disk, so nothing delays the CLI's result. The one-line log is suppressed in
   * [LeakDiagnosticsMode.OFF] (output only — counting still happens).
   */
  private fun checkForLeaks(): LeakSummary? {
    if (leakedClassLoaders.size <= 1) return null
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
        // `off` keeps counting active (consecutiveLeaks is updated above, and the leak-limit action
        // still trips) but emits no output — no one-line log, and no leak summary rides out on the
        // load report for the CLI to render. Returning null here suppresses both at once.
        if (leakDiagnostics == LeakDiagnosticsMode.OFF) return null
        logger.warning("$confirmed classloader(s) leaked (#$consecutiveLeaks)")
        return LeakSummary(
            consecutive = consecutiveLeaks,
            confirmedSurvivors = confirmed,
            attribution = buildAttribution(),
        )
      } else {
        consecutiveLeaks = 0
      }
    } else {
      consecutiveLeaks = 0
    }
    return null
  }

  /**
   * Cheap, synchronous attribution of what is pinning each still-surviving leaked classloader:
   * threads whose contextClassLoader is the dead loader, and async scheduler workers still owned by
   * the torn-down plugin. Kept out of the heap-walking [dumpVerboseDiagnostics] so it can run
   * inline during the reload and its findings can ride out on the result JSON for the CLI to
   * render.
   */
  private fun buildAttribution(): List<LeakAttribution> {
    val survivors = leakedClassLoaders.dropLast(1).mapNotNull { it.get() }.toSet()
    if (survivors.isEmpty()) return emptyList()
    val attribution = mutableListOf<LeakAttribution>()
    // Threads still rooted in a dead loader keep it — and its plugin — alive.
    for (thread in Thread.getAllStackTraces().keys) {
      if (thread.contextClassLoader in survivors) {
        attribution.add(
            LeakAttribution(
                kind = "thread",
                detail = "thread '${thread.name}' still running (stop it in onDisable())",
            )
        )
      }
    }
    // Async tasks whose owner plugin came from a dead loader hold it until they finish.
    val workers = runCatching { server.scheduler.activeWorkers }.getOrDefault(emptyList())
    for (worker in workers) {
      if (worker.owner.javaClass.classLoader in survivors) {
        attribution.add(
            LeakAttribution(
                kind = "scheduler",
                detail =
                    "async task from '${worker.owner.name}' still running — " +
                        "it holds the old plugin until it finishes",
            )
        )
      }
    }
    return attribution
  }

  /**
   * Diagnostic: dump what's keeping each surviving leaked classloader alive — class count, sample
   * class names, and any threads still rooted in it. Helps identify the actual pinner instead of
   * guessing at Paper subsystems one-by-one. Heavyweight (walks
   * [Instrumentation.allLoadedClasses]), so [BuildStatusBar] runs it on a deferred task AFTER the
   * load report is written, and only in [LeakDiagnosticsMode.FULL].
   */
  open fun dumpVerboseDiagnostics() {
    val survivors = leakedClassLoaders.dropLast(1).mapNotNull { it.get() }
    if (survivors.isEmpty()) return
    val inst = AgentAccess.instrumentation()
    val allLoaded = inst?.allLoadedClasses
    logger.warning("── leak diagnostics: ${survivors.size} surviving loader(s) ──")
    survivors.forEachIndexed { i, cl ->
      val tag = "loader#$i ${cl::class.java.simpleName}@${System.identityHashCode(cl).toString(16)}"
      val classes = allLoaded?.filter { it.classLoader === cl } ?: emptyList()
      val sample = classes.take(20).joinToString(", ") { it.name }
      logger.warning(
          "  $tag — ${classes.size} class(es) still loaded${if (sample.isNotEmpty()) ": $sample" else ""}"
      )
      val threads = Thread.getAllStackTraces().keys.filter { it.contextClassLoader === cl }
      if (threads.isNotEmpty()) {
        logger.warning("  $tag — ${threads.size} thread(s) with this contextClassLoader:")
        threads.forEach { t ->
          val frames =
              t.stackTrace.take(MAX_THREAD_STACK_FRAMES).joinToString("\n      ") { it.toString() }
          logger.warning("    '${t.name}' (state=${t.state}, daemon=${t.isDaemon})\n      $frames")
        }
      }
      val pluginsListed = scanPluginManagerForLoader(cl)
      logger.warning("  $tag — pluginManager.plugins scan: ${pluginsListed.size} match(es)")
      pluginsListed.forEach { logger.warning("    $it") }

      val cmdsListed = scanCommandMapForLoader(cl)
      logger.warning("  $tag — commandMap.knownCommands scan: ${cmdsListed.size} match(es)")
      cmdsListed.forEach { logger.warning("    $it") }

      val loggers = scanJulLoggersForLoader(cl)
      logger.warning(
          "  $tag — java.util.logging Loggers naming this loader's class: ${loggers.size}"
      )
      loggers.forEach { logger.warning("    $it") }

      val initiated = if (inst != null) inst.getInitiatedClasses(cl).size else -1
      logger.warning("  $tag — Instrumentation.getInitiatedClasses count: $initiated")
    }
    if (inst == null) {
      logger.warning("  (Instrumentation unavailable — class enumeration skipped)")
    }
    logger.warning("── end leak diagnostics ──")
  }

  private fun scanPluginManagerForLoader(cl: ClassLoader): List<String> {
    return try {
      server.pluginManager.plugins
          .filter { it.javaClass.classLoader === cl }
          .map {
            "${it.javaClass.name}@${System.identityHashCode(it).toString(16)} " +
                "(name=${it.name}, enabled=${it.isEnabled})"
          }
    } catch (
        @Suppress("TooGenericExceptionCaught") // Diagnostic — never throw out of leak reporting.
        e: Exception) {
      listOf("(scan failed: ${e.javaClass.simpleName}: ${e.message})")
    }
  }

  private fun scanCommandMapForLoader(cl: ClassLoader): List<String> {
    return try {
      val map = server.commandMap.knownCommands
      map.entries.mapNotNull { (k, c) ->
        val plugin = runCatching { c.javaClass.getMethod("getPlugin").invoke(c) }.getOrNull()
        if (plugin != null && plugin.javaClass.classLoader === cl) {
          "knownCommands[$k] -> ${c.javaClass.simpleName}: ${c.name}"
        } else null
      }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
      listOf("(scan failed: ${e.javaClass.simpleName}: ${e.message})")
    }
  }

  /**
   * j.u.l.LogManager keeps loggers in a global named map. Bukkit's PluginLogger names itself after
   * `plugin.getClass().getCanonicalName()`, so if the LogManager still has an entry named after a
   * class from this loader, that's a signal (though the value itself is WeakReference'd).
   */
  private fun scanJulLoggersForLoader(cl: ClassLoader): List<String> {
    return try {
      val lm = java.util.logging.LogManager.getLogManager()
      val names = lm.loggerNames.toList()
      names
          .filter { name ->
            // Best-effort: match logger names that look like classes we'd expect from this loader.
            // Without walking the heap, we can't directly tie a logger to a classloader, so we
            // probe by class-existence.
            try {
              val klass = Class.forName(name, false, cl)
              klass.classLoader === cl
            } catch (_: Throwable) {
              false
            }
          }
          .map { "Logger '$it'" }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
      listOf("(scan failed: ${e.javaClass.simpleName}: ${e.message})")
    }
  }

  private var heapDumped = false

  /**
   * Writes a one-shot live heap dump to [target] (the CLI reads `.paperplane/leak.hprof` under the
   * server root, not `user.dir`). Guarded to once per session — the first leak is the most
   * actionable and subsequent leaks share the same root. [BuildStatusBar] schedules this on an
   * async task in [LeakDiagnosticsMode.FULL], after the load report is written, since the dump is
   * the slowest step and must never delay the CLI's result.
   */
  open fun tryDumpHeap(target: File) {
    if (heapDumped) return
    heapDumped = true
    try {
      val mxBeanClass = Class.forName("com.sun.management.HotSpotDiagnosticMXBean")
      val mbeanServer = java.lang.management.ManagementFactory.getPlatformMBeanServer()
      val bean =
          java.lang.management.ManagementFactory.newPlatformMXBeanProxy(
              mbeanServer,
              "com.sun.management:type=HotSpotDiagnostic",
              mxBeanClass,
          )
      target.parentFile?.mkdirs()
      target.delete()
      val path = target.absolutePath
      mxBeanClass
          .getMethod("dumpHeap", String::class.java, Boolean::class.javaPrimitiveType)
          .invoke(bean, path, true /* live only */)
      logger.warning("  heap dump written to: $path  (open in Eclipse MAT / VisualVM)")
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
      logger.warning("  heap dump failed: ${e.javaClass.simpleName}: ${e.message}")
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

/**
 * One attributed source of a surviving classloader leak. `kind` ∈ thread|scheduler|command|unknown.
 */
data class LeakAttribution(val kind: String = "unknown", val detail: String = "")

/**
 * Leak accounting attached to a load result. `consecutive` counts back-to-back leaking reloads;
 * `confirmedSurvivors` is the absolute count of loaders still alive after double-GC. Populated by
 * the host's leak-detection path; absent (null) on a clean reload.
 */
data class LeakSummary(
    val consecutive: Int = 0,
    val confirmedSurvivors: Int = 0,
    val attribution: List<LeakAttribution> = emptyList(),
)

/**
 * Structured result the host reports back to the CLI (serialized to `.paperplane/load-complete` or
 * `load-failed`). Mirror of the CLI's `LoadReport`. `requestId` echoes the request so the CLI can
 * discard stale/torn results; `strategy` ∈ hotswap|fresh|reload.
 */
data class HostLoadReport(
    val requestId: String,
    val status: String,
    val strategy: String,
    val durationMs: Long,
    val pluginName: String,
    val message: String? = null,
    val leaks: LeakSummary? = null,
    val action: String? = null,
) {
  companion object {
    const val STATUS_OK = "ok"
    const val STATUS_FAILED = "failed"
    const val STRATEGY_HOTSWAP = "hotswap"
    const val STRATEGY_FRESH = "fresh"
    const val STRATEGY_RELOAD = "reload"
  }
}

sealed class HostLoadResult {
  abstract val durationMs: Long
  abstract val leaks: LeakSummary?
  abstract val action: String?

  /**
   * Re-stamps [durationMs], preserving every other field — [InnerPluginHost.handleRequest] uses
   * this to replace the inner paths' partial timings with the full end-to-end duration without
   * dropping what the leak-detection path attached to the result.
   */
  fun withDuration(durationMs: Long): HostLoadResult =
      when (this) {
        is Ok -> copy(durationMs = durationMs)
        is Failed -> copy(durationMs = durationMs)
      }

  data class Ok(
      val pluginName: String,
      override val durationMs: Long,
      override val leaks: LeakSummary? = null,
      override val action: String? = null,
  ) : HostLoadResult()

  data class Failed(
      val message: String,
      override val durationMs: Long,
      override val leaks: LeakSummary? = null,
      override val action: String? = null,
  ) : HostLoadResult()
}

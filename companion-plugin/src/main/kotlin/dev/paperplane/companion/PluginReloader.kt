package dev.paperplane.companion

import java.io.File
import java.lang.ref.WeakReference
import java.net.URLClassLoader
import java.util.Vector
import java.util.jar.JarFile
import java.util.logging.Logger
import org.bukkit.Server
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.java.JavaPlugin

enum class ReloadResult {
  SUCCESS,
  ROLLBACK_SUCCESS,
  ROLLBACK_FAILED,
  PLUGIN_NOT_FOUND,
  LOAD_FAILED,
  ENABLE_FAILED,
  REFLECTION_ERROR,
  NMS_DETECTED,
  HEALTH_CHECK_FAILED,
}

data class ReloadOutcome(
    val result: ReloadResult,
    val teardownMs: Long = 0,
    val loadMs: Long = 0,
    val totalMs: Long = 0,
)

@Suppress(
    "DEPRECATION"
) // SimplePluginManager is deprecated in Paper but still used for Bukkit-style plugins
class PluginReloader(private val server: Server, private val logger: Logger) {
  private val leakedClassLoaders = mutableListOf<WeakReference<ClassLoader>>()
  private var consecutiveLeaks = 0

  /** Whether hot-reload should be skipped due to repeated classloader leaks. */
  val shouldForceBlueGreen: Boolean
    get() = consecutiveLeaks >= 3

  // ── JAR-based reload (existing path) ───────────────────────────────

  fun reload(pluginName: String, jarFile: File, rollbackJar: File? = null): ReloadOutcome {
    val rollbackFn =
        if (
            rollbackJar != null &&
                rollbackJar.exists() &&
                rollbackJar.absolutePath != jarFile.absolutePath
        ) {
          { loadAndEnableFromJar(rollbackJar, pluginName) }
        } else null
    return reloadWithStrategy(pluginName, { loadAndEnableFromJar(jarFile, pluginName) }, rollbackFn)
  }

  // ── Directory-based reload (Level 1 HMR) ──────────────────────────

  /**
   * Reloads a plugin from build output directories instead of a JAR. Skips JAR packaging entirely —
   * loads classes from build/classes/ and dependencies from resolved runtime classpath JARs.
   */
  fun reloadFromDirectory(
      pluginName: String,
      buildOutputDirs: List<String>,
      rollbackJar: File? = null,
  ): ReloadOutcome {
    val rollbackFn =
        if (rollbackJar != null && rollbackJar.exists()) {
          { loadAndEnableFromJar(rollbackJar, pluginName) }
        } else null
    return reloadWithStrategy(
        pluginName,
        { loadAndEnableFromDirs(buildOutputDirs, pluginName) },
        rollbackFn,
    )
  }

  // ── Shared reload template ────────────────────────────────────────

  private fun reloadWithStrategy(
      pluginName: String,
      loadFn: () -> Plugin,
      rollbackFn: (() -> Plugin)?,
  ): ReloadOutcome {
    val totalStart = System.currentTimeMillis()

    if (shouldForceBlueGreen) {
      logger.warning(
          "Hot-reload disabled: $consecutiveLeaks consecutive classloader leaks detected"
      )
      return ReloadOutcome(ReloadResult.REFLECTION_ERROR)
    }

    val oldPlugin =
        server.pluginManager.getPlugin(pluginName)
            ?: return ReloadOutcome(ReloadResult.PLUGIN_NOT_FOUND)

    if (usesNmsClasses(oldPlugin)) {
      logger.warning("Plugin '$pluginName' uses NMS/CraftBukkit classes — skipping hot-reload")
      return ReloadOutcome(ReloadResult.NMS_DETECTED)
    }

    // === Phase A: Teardown ===
    val teardownStart = System.currentTimeMillis()
    val oldClassLoader = oldPlugin.javaClass.classLoader
    try {
      teardown(oldPlugin, pluginName)
    } catch (e: Exception) {
      logger.severe("Failed during plugin teardown: ${e.message}")
      e.printStackTrace()
      return ReloadOutcome(ReloadResult.REFLECTION_ERROR)
    }
    val teardownMs = System.currentTimeMillis() - teardownStart
    if (teardownMs > 3000) {
      logger.warning(
          "Plugin teardown took ${teardownMs}ms (>3s) — plugin's onDisable() may be slow"
      )
    }

    leakedClassLoaders.add(WeakReference(oldClassLoader))

    // === Phase B: Load new plugin (with rollback on failure) ===
    val loadStart = System.currentTimeMillis()
    val newPlugin: Plugin
    try {
      newPlugin = loadFn()
    } catch (e: Exception) {
      logger.severe("Failed to load plugin: ${e.message}")
      if (e.cause != null) logger.severe("Caused by: ${e.cause}")
      e.printStackTrace()

      if (rollbackFn != null) {
        logger.warning("Attempting rollback...")
        try {
          rollbackFn()
          val totalMs = System.currentTimeMillis() - totalStart
          logger.info("Rollback successful — old plugin restored")
          return ReloadOutcome(
              ReloadResult.ROLLBACK_SUCCESS,
              teardownMs,
              System.currentTimeMillis() - loadStart,
              totalMs,
          )
        } catch (rollbackEx: Exception) {
          logger.severe("Rollback also failed: ${rollbackEx.message}")
          rollbackEx.printStackTrace()
          val totalMs = System.currentTimeMillis() - totalStart
          return ReloadOutcome(
              ReloadResult.ROLLBACK_FAILED,
              teardownMs,
              System.currentTimeMillis() - loadStart,
              totalMs,
          )
        }
      }

      val totalMs = System.currentTimeMillis() - totalStart
      return ReloadOutcome(
          ReloadResult.LOAD_FAILED,
          teardownMs,
          System.currentTimeMillis() - loadStart,
          totalMs,
      )
    }
    val loadMs = System.currentTimeMillis() - loadStart
    if (loadMs > 3000) {
      logger.warning("Plugin load+enable took ${loadMs}ms (>3s) — plugin's onEnable() may be slow")
    }

    // === Phase C: Health verification ===
    return healthCheck(newPlugin, pluginName, teardownMs, loadMs, totalStart)
  }

  // ── Shared teardown ────────────────────────────────────────────────

  private fun teardown(plugin: Plugin, pluginName: String) {
    val classLoader = plugin.javaClass.classLoader

    // 1. Disable plugin (calls onDisable)
    server.pluginManager.disablePlugin(plugin)

    // 2. Unregister all event listeners
    HandlerList.unregisterAll(plugin)

    // 3. Cancel all scheduled tasks
    server.scheduler.cancelTasks(plugin)

    // 4. Unregister services
    server.servicesManager.unregisterAll(plugin)

    // 5. Unregister plugin messaging channels
    server.messenger.unregisterIncomingPluginChannel(plugin)
    server.messenger.unregisterOutgoingPluginChannel(plugin)

    // 6. Remove commands owned by the plugin
    PaperInternals.cleanupCommands(server, plugin, logger)

    // 7. Interrupt orphan threads spawned by the plugin
    interruptPluginThreads(classLoader)

    // 8. Close classloader to prevent stale class loading
    if (classLoader is URLClassLoader) {
      classLoader.close()
    }

    // 9. Remove from SimplePluginManager internals
    PaperInternals.removeFromPluginManager(server, plugin, pluginName)
  }

  // ── Shared health check ────────────────────────────────────────────

  private fun healthCheck(
      newPlugin: Plugin,
      pluginName: String,
      teardownMs: Long,
      loadMs: Long,
      totalStart: Long,
  ): ReloadOutcome {
    if (!newPlugin.isEnabled) {
      logger.severe("Health check failed: plugin '$pluginName' is not enabled after reload")
      val totalMs = System.currentTimeMillis() - totalStart
      return ReloadOutcome(ReloadResult.HEALTH_CHECK_FAILED, teardownMs, loadMs, totalMs)
    }

    PaperInternals.updatePaperPluginRegistry(server, pluginName, newPlugin, logger)
    checkForLeaks()

    val totalMs = System.currentTimeMillis() - totalStart
    return ReloadOutcome(ReloadResult.SUCCESS, teardownMs, loadMs, totalMs)
  }

  // ── JAR loading ────────────────────────────────────────────────────

  private fun loadAndEnableFromJar(jarFile: File, pluginName: String): Plugin {
    val jar = JarFile(jarFile)
    val pluginYmlEntry =
        jar.getJarEntry("plugin.yml")
            ?: throw IllegalStateException("No plugin.yml found in ${jarFile.name}")
    val description = PluginDescriptionFile(jar.getInputStream(pluginYmlEntry))
    jar.close()

    val (pluginInstance, classLoaderInstance) =
        PaperInternals.loadPluginFromJar(jarFile, description, server, logger)

    PaperInternals.registerInPluginManager(server, pluginInstance, pluginName)
    PaperInternals.registerCommands(server, pluginInstance, description, logger)
    server.pluginManager.enablePlugin(pluginInstance)
    PaperInternals.syncCommands(server)

    return pluginInstance
  }

  // ── Directory loading ──────────────────────────────────────────────

  private fun loadAndEnableFromDirs(dirs: List<String>, pluginName: String): Plugin {
    // 1. Find and read plugin.yml from one of the directories
    val resourcesDir =
        dirs.firstOrNull { File(it, "plugin.yml").exists() }
            ?: throw IllegalStateException(
                "No plugin.yml found in any build output directory. " +
                    "Searched: ${dirs.filter { File(it).isDirectory }.joinToString()}"
            )
    val description = PluginDescriptionFile(File(resourcesDir, "plugin.yml").inputStream())

    // 2. Build URL list: directories get trailing /, JARs are files
    val urls =
        dirs
            .map { path ->
              File(path).toURI().toURL() // File.toURI() adds trailing / for directories
            }
            .toTypedArray()

    // 3. Create classloader with cross-plugin visibility
    val classLoader = DevPluginClassLoader(urls, server.javaClass.classLoader, server.pluginManager)

    // 4. Instantiate plugin
    //    If the agent patched JavaPlugin's constructor, we can use normal newInstance()
    //    which correctly runs all constructors and field initializers.
    //    Otherwise, fall back to Unsafe.allocateInstance() (skips field initializers — risky).
    val mainClass = classLoader.loadClass(description.main)
    val pluginInstance: JavaPlugin =
        if (JavaPluginPatcher.isPatched) {
          mainClass.getDeclaredConstructor().newInstance() as JavaPlugin
        } else {
          logger.warning(
              "JavaPlugin not patched — using Unsafe.allocateInstance() (field initializers will be skipped)"
          )
          val unsafeClass = Class.forName("sun.misc.Unsafe")
          val unsafe =
              unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
          val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
          allocateInstance.invoke(unsafe, mainClass) as JavaPlugin
        }

    // 5. Initialize via PaperInternals (field injection — sets server, description, classLoader,
    // etc.)
    val dataFolder = File(server.pluginsFolder, description.name)
    PaperInternals.initializePlugin(
        pluginInstance,
        description,
        dataFolder,
        classLoader,
        server,
        logger,
    )

    // 6. Register + enable
    PaperInternals.registerInPluginManager(server, pluginInstance, pluginName)
    PaperInternals.registerCommands(server, pluginInstance, description, logger)
    server.pluginManager.enablePlugin(pluginInstance)
    PaperInternals.syncCommands(server)

    return pluginInstance
  }

  // ── NMS detection ──────────────────────────────────────────────────

  private fun usesNmsClasses(plugin: Plugin): Boolean {
    try {
      val classLoader = plugin.javaClass.classLoader
      val classesField = ClassLoader::class.java.getDeclaredField("classes")
      classesField.isAccessible = true
      @Suppress("UNCHECKED_CAST") val classes = classesField.get(classLoader) as Vector<Class<*>>
      val snapshot: List<Class<*>> = synchronized(classes) { classes.toList() }
      return snapshot.any { clazz: Class<*> ->
        clazz.name.startsWith("net.minecraft.") || clazz.name.startsWith("org.bukkit.craftbukkit.")
      }
    } catch (_: Exception) {
      return false
    }
  }

  // ── Thread cleanup ─────────────────────────────────────────────────

  private fun interruptPluginThreads(classLoader: ClassLoader) {
    val pluginThreads =
        Thread.getAllStackTraces().keys.filter {
          it.contextClassLoader == classLoader && it != Thread.currentThread()
        }

    for (thread in pluginThreads) {
      thread.interrupt()
    }
    if (pluginThreads.isNotEmpty()) {
      logger.warning("Interrupted ${pluginThreads.size} orphan thread(s) from old plugin")
    }

    for (thread in pluginThreads) {
      thread.join(2000)
      if (thread.isAlive) {
        val stack = thread.stackTrace.take(5).joinToString("\n    ") { it.toString() }
        logger.warning("Thread '${thread.name}' did not stop after interrupt:\n    $stack")
      }
    }
  }

  // ── Classloader leak detection ─────────────────────────────────────

  private fun nudgeGc() {
    System.gc()
    try {
      Thread.sleep(100)
    } catch (_: InterruptedException) {}
  }

  private fun checkForLeaks() {
    if (leakedClassLoaders.size <= 1) return

    // Give GC a chance to collect old classloaders before checking
    nudgeGc()

    val toCheck = leakedClassLoaders.dropLast(1)
    val stillLeaking = toCheck.count { it.get() != null }

    leakedClassLoaders.removeAll { ref -> ref != leakedClassLoaders.last() && ref.get() == null }

    if (stillLeaking > 0) {
      // Retry once — GC is non-deterministic, avoid false positives
      nudgeGc()
      val confirmedLeaking = leakedClassLoaders.dropLast(1).count { it.get() != null }
      leakedClassLoaders.removeAll { ref -> ref != leakedClassLoaders.last() && ref.get() == null }

      if (confirmedLeaking > 0) {
        consecutiveLeaks++
        logger.warning(
            "$confirmedLeaking classloader(s) not collected after full reload cycle (leak #$consecutiveLeaks)"
        )
        if (consecutiveLeaks >= 3) {
          logger.warning(
              "Too many classloader leaks — hot-reload will be disabled for this session"
          )
        }
      } else {
        consecutiveLeaks = 0
      }
    } else {
      consecutiveLeaks = 0
    }
  }
}

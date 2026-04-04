package dev.paperplane.companion

import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.jar.JarFile
import java.util.logging.Logger
import org.bukkit.Server
import org.bukkit.command.PluginCommand
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.SimplePluginManager
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.java.JavaPluginLoader

/**
 * Encapsulates ALL reflection into Paper/Bukkit internals.
 *
 * Every reflective access point is in this file so that when Paper changes internal class layouts
 * (constructor signatures, field names, wrapper structures), only this file needs updating.
 * Resolution results are cached per session.
 */
@Suppress("DEPRECATION")
object PaperInternals {

  // ── Cached reflection results ──────────────────────────────────────

  private var pluginClassLoaderCtor: Constructor<*>? = null
  private var spmPluginsField: Field? = null
  private var spmLookupField: Field? = null
  private var cachedSpm: SimplePluginManager? = null

  private val paperVersion: String by lazy {
    try {
      org.bukkit.Bukkit.getServer()?.javaClass?.`package`?.implementationVersion ?: "unknown"
    } catch (_: Exception) {
      "unknown"
    }
  }

  fun clearCaches() {
    pluginClassLoaderCtor = null
    spmPluginsField = null
    spmLookupField = null
    cachedSpm = null
  }

  // ── PluginClassLoader construction (for JAR-based loading) ─────────

  /**
   * Creates a plugin instance from a JAR file using Paper's PluginClassLoader via reflection. This
   * is the JAR-based loading path — directory loading bypasses this entirely.
   */
  fun loadPluginFromJar(
      jarFile: File,
      description: PluginDescriptionFile,
      server: Server,
      logger: Logger,
  ): Pair<JavaPlugin, ClassLoader> {
    val dataFolder = File(server.pluginsFolder, description.name)
    val loader = JavaPluginLoader(server)

    val pclClass = Class.forName("org.bukkit.plugin.java.PluginClassLoader")
    val constructor = resolvePluginClassLoaderConstructor(pclClass)

    val jarFileObj = JarFile(jarFile)
    val params = constructor.parameterTypes
    val args =
        Array<Any?>(params.size) { i ->
          when {
            params[i] == ClassLoader::class.java && i == 0 -> server.javaClass.classLoader
            PluginDescriptionFile::class.java.isAssignableFrom(params[i]) -> description
            params[i] == File::class.java && i == params.indexOfFirst { it == File::class.java } ->
                dataFolder
            params[i] == File::class.java -> jarFile
            params[i] == ClassLoader::class.java -> null // libraryLoader
            params[i] == JarFile::class.java -> jarFileObj
            else -> null // DependencyContext or unknown — nullable
          }
        }

    val classLoaderInstance: Any = constructor.newInstance(*args)

    // Set the loader field
    try {
      val loaderField = pclClass.getDeclaredField("loader")
      loaderField.isAccessible = true
      loaderField.set(classLoaderInstance, loader)
    } catch (e: Exception) {
      logger.warning("Could not set loader field on PluginClassLoader: ${e.message}")
    }

    // Get the plugin instance — try known field names
    var pluginInstance: JavaPlugin? = null
    for (fieldName in listOf("pluginInit", "plugin")) {
      try {
        val f = pclClass.getDeclaredField(fieldName)
        f.isAccessible = true
        val v = f.get(classLoaderInstance)
        if (v is JavaPlugin) {
          pluginInstance = v
          break
        }
      } catch (_: Exception) {}
    }

    if (pluginInstance == null) {
      val mainClass = (classLoaderInstance as ClassLoader).loadClass(description.main)
      pluginInstance = mainClass.getDeclaredConstructor().newInstance() as JavaPlugin
    }

    // Ensure init() has been called
    initializePlugin(
        pluginInstance,
        description,
        dataFolder,
        classLoaderInstance as ClassLoader,
        server,
        logger,
        jarFile,
    )

    return pluginInstance to classLoaderInstance
  }

  private fun resolvePluginClassLoaderConstructor(pclClass: Class<*>): Constructor<*> {
    pluginClassLoaderCtor?.let {
      return it
    }

    val ctor =
        pclClass.declaredConstructors
            .filter { it.parameterCount >= 5 }
            .maxByOrNull { it.parameterCount }
            ?: throw IllegalStateException(
                "No suitable PluginClassLoader constructor found (Paper $paperVersion). " +
                    "Available: ${pclClass.declaredConstructors.map {
                    "${it.parameterCount} params: [${it.parameterTypes.joinToString { p -> p.simpleName }}]"
                }}"
            )

    ctor.isAccessible = true
    pluginClassLoaderCtor = ctor
    return ctor
  }

  // ── JavaPlugin initialization ──────────────────────────────────────

  /**
   * Initializes a JavaPlugin instance. Tries JavaPlugin.init() first, falls back to direct field
   * injection.
   */
  fun initializePlugin(
      plugin: JavaPlugin,
      description: PluginDescriptionFile,
      dataFolder: File,
      classLoader: ClassLoader,
      server: Server,
      logger: Logger,
      jarFile: File? = null,
  ) {
    // Try the init() method first
    val initSucceeded = tryInit(plugin, description, dataFolder, classLoader, server, jarFile)
    if (initSucceeded) return

    // Fallback: direct field injection
    logger.warning("JavaPlugin.init() unavailable (Paper $paperVersion), using field injection")
    injectFields(plugin, description, dataFolder, classLoader, server, logger, jarFile)
  }

  private fun tryInit(
      plugin: JavaPlugin,
      description: PluginDescriptionFile,
      dataFolder: File,
      classLoader: ClassLoader,
      server: Server,
      jarFile: File?,
  ): Boolean {
    return try {
      val initMethod =
          JavaPlugin::class
              .java
              .getDeclaredMethod(
                  "init",
                  org.bukkit.plugin.PluginLoader::class.java,
                  Server::class.java,
                  PluginDescriptionFile::class.java,
                  File::class.java,
                  File::class.java,
                  ClassLoader::class.java,
              )
      initMethod.isAccessible = true
      val loader = JavaPluginLoader(server)
      initMethod.invoke(
          plugin,
          loader,
          server,
          description,
          dataFolder,
          jarFile ?: dataFolder,
          classLoader,
      )
      true
    } catch (_: Exception) {
      false
    }
  }

  private fun injectFields(
      plugin: JavaPlugin,
      description: PluginDescriptionFile,
      dataFolder: File,
      classLoader: ClassLoader,
      server: Server,
      logger: Logger,
      jarFile: File?,
  ) {
    val criticalFields = setOf("server", "description", "classLoader")
    val setFields = mutableSetOf<String>()

    for ((name, value) in
        mapOf(
            "server" to server,
            "description" to description,
            "dataFolder" to dataFolder,
            "file" to (jarFile ?: dataFolder),
            "classLoader" to classLoader,
        )) {
      try {
        val f = JavaPlugin::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(plugin, value)
        setFields.add(name)
      } catch (e: Exception) {
        logger.warning("Failed to set JavaPlugin.$name: ${e.message}")
      }
    }

    // Also try PluginBase.loader
    try {
      val loaderF = Class.forName("org.bukkit.plugin.PluginBase").getDeclaredField("loader")
      loaderF.isAccessible = true
      loaderF.set(plugin, JavaPluginLoader(server))
    } catch (e: Exception) {
      logger.warning("Failed to set PluginBase.loader: ${e.message}")
    }

    val missing = criticalFields - setFields
    if (missing.isNotEmpty()) {
      throw IllegalStateException(
          "Critical JavaPlugin fields not set (Paper $paperVersion): $missing. " +
              "Hot-reload may not work with this Paper version."
      )
    }
  }

  // ── SimplePluginManager operations ─────────────────────────────────

  /**
   * Gets the SimplePluginManager, unwrapping Paper's wrapper if needed. Caches the result for the
   * session.
   */
  fun unwrapSimplePluginManager(server: Server): SimplePluginManager? {
    cachedSpm?.let {
      return it
    }

    val pm = server.pluginManager

    // Direct cast
    if (pm is SimplePluginManager) {
      cachedSpm = pm
      return pm
    }

    // Paper wraps it — search fields on the wrapper and its superclasses
    var clazz: Class<*>? = pm.javaClass
    while (clazz != null && clazz != Any::class.java) {
      for (field in clazz.declaredFields) {
        try {
          field.isAccessible = true
          val value = field.get(pm)
          if (value is SimplePluginManager) {
            cachedSpm = value
            return value
          }
        } catch (_: Exception) {}
      }
      clazz = clazz.superclass
    }

    return null
  }

  /** Registers a plugin in SimplePluginManager's internal lists. */
  fun registerInPluginManager(server: Server, plugin: Plugin, pluginName: String) {
    val spm =
        unwrapSimplePluginManager(server)
            ?: throw IllegalStateException(
                "Could not find SimplePluginManager (Paper $paperVersion)"
            )

    val pluginsField = resolveSpmPluginsField(spm)
    @Suppress("UNCHECKED_CAST") val plugins = pluginsField.get(spm) as MutableList<Plugin>
    plugins.add(plugin)

    val lookupField = resolveSpmLookupField(spm)
    @Suppress("UNCHECKED_CAST") val lookupNames = lookupField.get(spm) as MutableMap<String, Plugin>
    lookupNames[pluginName.lowercase()] = plugin
  }

  /** Removes a plugin from SimplePluginManager's internal lists. */
  fun removeFromPluginManager(server: Server, plugin: Plugin, pluginName: String) {
    val spm =
        unwrapSimplePluginManager(server)
            ?: throw IllegalStateException(
                "Could not find SimplePluginManager (Paper $paperVersion)"
            )

    val pluginsField = resolveSpmPluginsField(spm)
    @Suppress("UNCHECKED_CAST") val plugins = pluginsField.get(spm) as MutableList<Plugin>
    plugins.remove(plugin)

    val lookupField = resolveSpmLookupField(spm)
    @Suppress("UNCHECKED_CAST") val lookupNames = lookupField.get(spm) as MutableMap<String, Plugin>
    lookupNames.remove(pluginName.lowercase())
  }

  private fun resolveSpmPluginsField(spm: SimplePluginManager): Field {
    spmPluginsField?.let {
      return it
    }
    val f = SimplePluginManager::class.java.getDeclaredField("plugins")
    f.isAccessible = true
    spmPluginsField = f
    return f
  }

  private fun resolveSpmLookupField(spm: SimplePluginManager): Field {
    spmLookupField?.let {
      return it
    }
    val f = SimplePluginManager::class.java.getDeclaredField("lookupNames")
    f.isAccessible = true
    spmLookupField = f
    return f
  }

  // ── Command registration ───────────────────────────────────────────

  /** Registers commands from plugin.yml into the server's command map. */
  fun registerCommands(
      server: Server,
      plugin: JavaPlugin,
      description: PluginDescriptionFile,
      logger: Logger,
  ) {
    try {
      val commandMap = server.commandMap
      for ((name, cmdInfo) in description.commands) {
        val command =
            PluginCommand::class
                .java
                .getDeclaredConstructor(String::class.java, Plugin::class.java)
                .apply { isAccessible = true }
                .newInstance(name, plugin)

        if (cmdInfo is Map<*, *>) {
          (cmdInfo["description"] as? String)?.let { command.description = it }
          (cmdInfo["usage"] as? String)?.let { command.usage = it }
          (cmdInfo["permission"] as? String)?.let { command.permission = it }
          val aliases = cmdInfo["aliases"]
          if (aliases is List<*>) {
            command.aliases = aliases.filterIsInstance<String>()
          } else if (aliases is String) {
            command.aliases = listOf(aliases)
          }
        }

        commandMap.register(description.name, command)
      }
    } catch (e: Exception) {
      logger.warning("Failed to register commands from plugin.yml: ${e.message}")
    }
  }

  /** Removes commands registered by a plugin from the server's command map. */
  fun cleanupCommands(server: Server, plugin: Plugin, logger: Logger) {
    try {
      val commandMap = server.commandMap
      val knownCommands = commandMap.knownCommands
      val pluginNameLower = plugin.name.lowercase()

      val pluginCommandNames =
          try {
            plugin.description.commands.keys.map { it.lowercase() }
          } catch (_: Exception) {
            emptyList()
          }

      val toRemove =
          knownCommands.entries
              .filter { (key, cmd) ->
                (cmd is PluginCommand && cmd.plugin == plugin) ||
                    key.startsWith("$pluginNameLower:") ||
                    key in pluginCommandNames ||
                    knownCommands.containsKey("$pluginNameLower:$key")
              }
              .map { it.key }

      for (key in toRemove) {
        knownCommands.remove(key)
      }

      syncCommands(server)

      if (toRemove.isNotEmpty()) {
        logger.info("Removed ${toRemove.size} command(s): $toRemove")
      }
    } catch (e: Exception) {
      logger.warning("Failed to clean up commands: ${e.message}")
    }
  }

  // ── Brigadier sync ─────────────────────────────────────────────────

  /** Syncs the Brigadier command tree. Gracefully no-ops if unavailable. */
  fun syncCommands(server: Server) {
    try {
      server.javaClass.getMethod("syncCommands").invoke(server)
    } catch (_: Exception) {
      // Paper internal method — may not exist in all versions
    }
  }

  // ── Paper plugin registry update ───────────────────────────────────

  /**
   * Updates Paper's internal plugin registry (separate from SimplePluginManager) to point to a new
   * plugin instance after reload.
   */
  fun updatePaperPluginRegistry(
      server: Server,
      pluginName: String,
      newPlugin: Plugin,
      logger: Logger,
  ) {
    var updated = false
    val visited = mutableSetOf<Int>()
    val queue = ArrayDeque<Pair<Any, Int>>()
    queue.add(server.pluginManager to 0)

    while (queue.isNotEmpty()) {
      val (obj, depth) = queue.removeFirst()
      if (depth > 4 || !visited.add(System.identityHashCode(obj))) continue
      val cls = obj.javaClass
      val cn = cls.name
      if (
          cn.startsWith("java.") ||
              cn.startsWith("jdk.") ||
              cn.startsWith("sun.") ||
              cn.startsWith("javax.") ||
              cn.startsWith("kotlin.")
      )
          continue

      var currentClass: Class<*>? = cls
      while (currentClass != null && currentClass != Any::class.java) {
        for (field in currentClass.declaredFields) {
          try {
            field.isAccessible = true
            val value = field.get(obj) ?: continue

            if (value is MutableList<*>) {
              val idx = value.indexOfFirst { (it as? Plugin)?.name == pluginName }
              if (idx >= 0) {
                @Suppress("UNCHECKED_CAST")
                (value as MutableList<Any>)[idx] = newPlugin
                updated = true
              }
            }
            if (value is MutableMap<*, *>) {
              for (key in listOf(pluginName, pluginName.lowercase())) {
                val existing = value[key]
                if (existing is Plugin && existing.name == pluginName) {
                  @Suppress("UNCHECKED_CAST")
                  (value as MutableMap<String, Any>)[key] = newPlugin
                  updated = true
                }
              }
            }
            val vcn = value.javaClass.name
            if (
                !vcn.startsWith("java.") &&
                    !vcn.startsWith("jdk.") &&
                    !vcn.startsWith("sun.") &&
                    !vcn.startsWith("javax.") &&
                    !vcn.startsWith("kotlin.") &&
                    depth < 4
            ) {
              queue.add(value to depth + 1)
            }
          } catch (_: Exception) {}
        }
        currentClass = currentClass.superclass
      }
    }
    if (!updated) {
      logger.warning(
          "Could not find plugin '$pluginName' in Paper's internal registry (Paper $paperVersion)"
      )
    }
  }
}

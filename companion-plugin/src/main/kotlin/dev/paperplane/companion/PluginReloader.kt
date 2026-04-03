package dev.paperplane.companion

import org.bukkit.Server
import org.bukkit.command.PluginCommand
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.PluginLoader
import org.bukkit.plugin.SimplePluginManager
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.java.JavaPluginLoader
import java.io.File
import java.lang.ref.WeakReference
import java.net.URLClassLoader
import java.util.Vector
import java.util.jar.JarFile
import java.util.logging.Logger

enum class ReloadResult {
    SUCCESS,
    PLUGIN_NOT_FOUND,
    LOAD_FAILED,
    ENABLE_FAILED,
    REFLECTION_ERROR,
    NMS_DETECTED,
    HEALTH_CHECK_FAILED
}

@Suppress("DEPRECATION") // SimplePluginManager is deprecated in Paper but still used for Bukkit-style plugins
class PluginReloader(
    private val server: Server,
    private val logger: Logger
) {
    private val leakedClassLoaders = mutableListOf<WeakReference<ClassLoader>>()
    private var consecutiveLeaks = 0

    /**
     * Whether hot-reload should be skipped due to repeated classloader leaks.
     */
    val shouldForceBlueGreen: Boolean
        get() = consecutiveLeaks >= 3

    fun reload(pluginName: String, jarFile: File): ReloadResult {
        if (shouldForceBlueGreen) {
            logger.warning("Hot-reload disabled: $consecutiveLeaks consecutive classloader leaks detected")
            return ReloadResult.REFLECTION_ERROR
        }

        val oldPlugin = server.pluginManager.getPlugin(pluginName)
            ?: return ReloadResult.PLUGIN_NOT_FOUND

        // --- NMS detection: check if plugin uses internal server classes ---
        if (usesNmsClasses(oldPlugin)) {
            logger.warning("Plugin '$pluginName' uses NMS/CraftBukkit classes — skipping hot-reload")
            return ReloadResult.NMS_DETECTED
        }

        val oldClassLoader = oldPlugin.javaClass.classLoader

        // === Phase A: Teardown ===
        try {
            // 1. Disable plugin (calls onDisable)
            server.pluginManager.disablePlugin(oldPlugin)

            // 2. Unregister all event listeners
            HandlerList.unregisterAll(oldPlugin)

            // 3. Cancel all scheduled tasks
            server.scheduler.cancelTasks(oldPlugin)

            // 4. Unregister services
            server.servicesManager.unregisterAll(oldPlugin)

            // 5. Unregister plugin messaging channels
            server.messenger.unregisterIncomingPluginChannel(oldPlugin)
            server.messenger.unregisterOutgoingPluginChannel(oldPlugin)

            // 6. Remove commands owned by the plugin
            cleanupCommands(oldPlugin)

            // 7. Interrupt orphan threads spawned by the plugin
            interruptPluginThreads(oldClassLoader)

            // 8. Close classloader to prevent stale class loading
            if (oldClassLoader is URLClassLoader) {
                oldClassLoader.close()
            }

            // 9. Remove from SimplePluginManager internals
            removeFromPluginManager(oldPlugin, pluginName)

        } catch (e: Exception) {
            logger.severe("Failed during plugin teardown: ${e.message}")
            e.printStackTrace()
            return ReloadResult.REFLECTION_ERROR
        }

        // Track classloader for leak detection
        val classLoaderRef = WeakReference(oldClassLoader)
        leakedClassLoaders.add(classLoaderRef)

        // === Phase B: Load ===
        // Paper 1.21+ broke SimplePluginManager.loadPlugin() and JavaPluginLoader.loadPlugin().
        // JavaPlugin's constructor also checks (classLoader instanceof PluginClassLoader).
        // We must create a PluginClassLoader via reflection — it handles everything internally:
        // reading plugin.yml, creating the classloader, loading the main class, calling init().
        val newPlugin: Plugin
        try {
            // 10. Read plugin.yml for description
            val jar = JarFile(jarFile)
            val pluginYmlEntry = jar.getJarEntry("plugin.yml")
                ?: throw IllegalStateException("No plugin.yml found in ${jarFile.name}")
            val description = PluginDescriptionFile(jar.getInputStream(pluginYmlEntry))
            jar.close()

            val dataFolder = File(jarFile.parentFile, description.name)
            val loader = JavaPluginLoader(server)

            // Create PluginClassLoader via reflection — it instantiates the plugin and calls init()
            // Paper 1.21.10 constructor: (ClassLoader parent, PluginDescriptionFile, File dataFolder,
            //   File jarFile, ClassLoader libraryLoader, JarFile jar, DependencyContext)
            val pclClass = Class.forName("org.bukkit.plugin.java.PluginClassLoader")
            val constructor = pclClass.declaredConstructors.firstOrNull { it.parameterCount >= 5 }
                ?: throw IllegalStateException("Could not find PluginClassLoader constructor")
            constructor.isAccessible = true

            val jarFileObj = JarFile(jarFile)
            val params = constructor.parameterTypes
            val args = Array<Any?>(params.size) { i ->
                when {
                    params[i] == ClassLoader::class.java && i == 0 -> server.javaClass.classLoader // parent
                    PluginDescriptionFile::class.java.isAssignableFrom(params[i]) -> description
                    params[i] == File::class.java && i == params.indexOfFirst { it == File::class.java } -> dataFolder
                    params[i] == File::class.java -> jarFile
                    params[i] == ClassLoader::class.java -> null // libraryLoader
                    params[i] == JarFile::class.java -> jarFileObj
                    else -> null // DependencyContext or unknown — nullable
                }
            }

            // Set the loader field on the CLASS before construction so init() can find it.
            // Paper 1.21 PluginClassLoader doesn't take loader as a constructor param,
            // but JavaPlugin.init() needs it. We pre-set it via a field default workaround:
            // actually we can't set instance fields before construction, so we set loader
            // after construction and then manually re-init the plugin.
            val classLoaderInstance: Any = constructor.newInstance(*args)

            // Set the loader field
            val loaderField = pclClass.getDeclaredField("loader")
            loaderField.isAccessible = true
            loaderField.set(classLoaderInstance, loader)

            // Get the plugin instance — try both 'pluginInit' and 'plugin' fields
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
                // Constructor didn't create the plugin — do it manually
                val mainClass = (classLoaderInstance as ClassLoader).loadClass(description.main)
                pluginInstance = mainClass.getDeclaredConstructor().newInstance() as JavaPlugin
            }

            // Ensure JavaPlugin.init() has been called with a valid loader
            // Re-call init() with the correct loader (this is idempotent for field setting)
            try {
                val initMethod = JavaPlugin::class.java.getDeclaredMethod(
                    "init",
                    org.bukkit.plugin.PluginLoader::class.java,
                    Server::class.java,
                    PluginDescriptionFile::class.java,
                    File::class.java,
                    File::class.java,
                    ClassLoader::class.java
                )
                initMethod.isAccessible = true
                initMethod.invoke(pluginInstance, loader, server, description, dataFolder, jarFile, classLoaderInstance)
            } catch (_: Exception) {
                // init() might not exist in this Paper version — try setting fields directly
                for ((name, value) in mapOf(
                    "server" to server,
                    "description" to description,
                    "dataFolder" to dataFolder,
                    "file" to jarFile,
                    "classLoader" to classLoaderInstance
                )) {
                    try {
                        val f = JavaPlugin::class.java.getDeclaredField(name)
                        f.isAccessible = true
                        f.set(pluginInstance, value)
                    } catch (_: Exception) {}
                }
                // Also try PluginBase fields
                try {
                    val loaderF = Class.forName("org.bukkit.plugin.PluginBase").getDeclaredField("loader")
                    loaderF.isAccessible = true
                    loaderF.set(pluginInstance, loader)
                } catch (_: Exception) {}
            }

            newPlugin = pluginInstance

            // Register in SimplePluginManager's internal lists
            val spm = unwrapSimplePluginManager()
            if (spm != null) {
                val pluginsField = SimplePluginManager::class.java.getDeclaredField("plugins")
                pluginsField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val plugins = pluginsField.get(spm) as MutableList<Plugin>
                plugins.add(newPlugin)

                val lookupField = SimplePluginManager::class.java.getDeclaredField("lookupNames")
                lookupField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val lookupNames = lookupField.get(spm) as MutableMap<String, Plugin>
                lookupNames[pluginName.lowercase()] = newPlugin
            }
        } catch (e: Exception) {
            logger.severe("Failed to load plugin from ${jarFile.name}: ${e.message}")
            if (e.cause != null) logger.severe("Caused by: ${e.cause}")
            e.printStackTrace()
            return ReloadResult.LOAD_FAILED
        }

        try {
            // 11. Enable new plugin (calls onEnable via the plugin's loader)
            server.pluginManager.enablePlugin(newPlugin)
        } catch (e: Exception) {
            logger.severe("Failed to enable plugin '$pluginName': ${e.message}")
            e.printStackTrace()
            return ReloadResult.ENABLE_FAILED
        }

        // === Phase C: Health verification ===
        // 12. Verify the plugin is actually enabled
        // Note: getPlugin() may return the old instance from Paper's internal registry
        // (separate from SimplePluginManager). Check newPlugin directly.
        if (!newPlugin.isEnabled) {
            logger.severe("Health check failed: plugin '$pluginName' is not enabled after reload")
            return ReloadResult.HEALTH_CHECK_FAILED
        }

        // Update Paper's internal plugin registry to point to the new instance
        updatePaperPluginRegistry(pluginName, newPlugin)

        // Check for classloader leaks from previous reloads
        checkForLeaks()

        logger.info("Successfully hot-reloaded '$pluginName'")
        return ReloadResult.SUCCESS
    }

    /**
     * Detects if the plugin loaded any NMS or CraftBukkit classes,
     * which can't be safely cleaned up during hot-reload.
     */
    private fun usesNmsClasses(plugin: Plugin): Boolean {
        try {
            val classLoader = plugin.javaClass.classLoader
            // ClassLoader.classes is a protected Vector<Class<?>>
            val classesField = ClassLoader::class.java.getDeclaredField("classes")
            classesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val classes = classesField.get(classLoader) as Vector<Class<*>>

            // Snapshot to avoid ConcurrentModificationException
            val snapshot: List<Class<*>> = synchronized(classes) { classes.toList() }
            return snapshot.any { clazz: Class<*> ->
                clazz.name.startsWith("net.minecraft.") || clazz.name.startsWith("org.bukkit.craftbukkit.")
            }
        } catch (_: Exception) {
            // If we can't check, assume safe (don't block reload unnecessarily)
            return false
        }
    }

    /**
     * Removes commands registered by the plugin from the server's command map.
     * Paper exposes commandMap publicly.
     */
    private fun cleanupCommands(plugin: Plugin) {
        try {
            val commandMap = server.commandMap
            val knownCommands = commandMap.knownCommands
            val pluginNameLower = plugin.name.lowercase()

            // Collect command names registered by this plugin from its description
            val pluginCommandNames = try {
                plugin.description.commands.keys.map { it.lowercase() }
            } catch (_: Exception) { emptyList() }

            val toRemove = knownCommands.entries.filter { (key, cmd) ->
                // Match PluginCommands owned by this plugin
                (cmd is PluginCommand && cmd.plugin == plugin) ||
                // Match commands with the plugin's prefix (e.g. "hrtest:hrtest")
                key.startsWith("$pluginNameLower:") ||
                // Match bare command names from plugin.yml
                key in pluginCommandNames ||
                // Match bare command names that also have a prefixed entry (registered by this plugin)
                knownCommands.containsKey("$pluginNameLower:$key")
            }.map { it.key }

            for (key in toRemove) {
                knownCommands.remove(key)
            }

            // Also remove any Brigadier registrations by syncing commands after cleanup
            try {
                server.javaClass.getMethod("syncCommands").invoke(server)
            } catch (_: Exception) {}

            if (toRemove.isNotEmpty()) {
                logger.info("Removed ${toRemove.size} command(s): $toRemove")
            }
        } catch (e: Exception) {
            logger.warning("Failed to clean up commands: ${e.message}")
        }
    }

    /**
     * Finds and interrupts threads that were spawned by the old plugin.
     * Detects by checking if the thread's context classloader matches the plugin's.
     */
    private fun interruptPluginThreads(classLoader: ClassLoader) {
        var interrupted = 0
        for ((thread, _) in Thread.getAllStackTraces()) {
            if (thread.contextClassLoader == classLoader && thread != Thread.currentThread()) {
                thread.interrupt()
                interrupted++
            }
        }
        if (interrupted > 0) {
            logger.info("Interrupted $interrupted orphan thread(s) from old plugin")
        }
    }

    /**
     * Updates Paper's internal plugin registry (PaperPluginManagerImpl) to point to the new plugin instance.
     * Paper maintains its own plugin map separate from SimplePluginManager.
     */
    private fun updatePaperPluginRegistry(pluginName: String, newPlugin: Plugin) {
        try {
            val pm = server.pluginManager
            // Search for an instance manager field that holds plugin instances
            for (field in pm.javaClass.declaredFields) {
                field.isAccessible = true
                val value = field.get(pm) ?: continue
                // Look for fields that have methods to manage plugins
                for (innerField in value.javaClass.declaredFields) {
                    innerField.isAccessible = true
                    val innerValue = try { innerField.get(value) } catch (_: Exception) { continue }
                    // Look for a List<Plugin> or Map containing plugins
                    if (innerValue is MutableList<*>) {
                        val idx = innerValue.indexOfFirst { (it as? Plugin)?.name == pluginName }
                        if (idx >= 0) {
                            @Suppress("UNCHECKED_CAST")
                            (innerValue as MutableList<Any>)[idx] = newPlugin
                            logger.info("Updated Paper plugin list: ${innerField.name}")
                        }
                    }
                    if (innerValue is MutableMap<*, *>) {
                        if (innerValue.containsKey(pluginName) || innerValue.containsKey(pluginName.lowercase())) {
                            @Suppress("UNCHECKED_CAST")
                            val map = innerValue as MutableMap<String, Any>
                            val key = if (map.containsKey(pluginName)) pluginName else pluginName.lowercase()
                            map[key] = newPlugin
                            logger.info("Updated Paper plugin map: ${innerField.name}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warning("Could not update Paper's plugin registry: ${e.message}")
        }
    }

    /**
     * Finds a suitable PluginClassLoader constructor via reflection.
     * Handles different Paper versions which may have different constructor signatures.
     */
    private fun findPluginClassLoaderConstructor(pclClass: Class<*>): java.lang.reflect.Constructor<*>? {
        // Try to find a constructor that takes a JavaPluginLoader as first param
        for (constructor in pclClass.declaredConstructors) {
            val params = constructor.parameterTypes
            if (params.size >= 5 &&
                JavaPluginLoader::class.java.isAssignableFrom(params[0]) &&
                ClassLoader::class.java.isAssignableFrom(params[1]) &&
                PluginDescriptionFile::class.java.isAssignableFrom(params[2]) &&
                File::class.java.isAssignableFrom(params[3]) &&
                File::class.java.isAssignableFrom(params[4])) {
                return constructor
            }
        }

        // Fallback: find any constructor with PluginDescriptionFile and File params
        for (constructor in pclClass.declaredConstructors) {
            val params = constructor.parameterTypes
            if (params.any { PluginDescriptionFile::class.java.isAssignableFrom(it) } &&
                params.any { File::class.java.isAssignableFrom(it) }) {
                return constructor
            }
        }

        return null
    }

    /**
     * Removes the plugin from SimplePluginManager's internal data structures via reflection.
     * Handles Paper's wrapper around SimplePluginManager.
     */
    private fun removeFromPluginManager(plugin: Plugin, pluginName: String) {
        val spm = unwrapSimplePluginManager()
            ?: throw IllegalStateException("Could not find SimplePluginManager")

        // Remove from plugins list
        val pluginsField = SimplePluginManager::class.java.getDeclaredField("plugins")
        pluginsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val plugins = pluginsField.get(spm) as MutableList<Plugin>
        plugins.remove(plugin)

        // Remove from lookup map
        val lookupField = SimplePluginManager::class.java.getDeclaredField("lookupNames")
        lookupField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val lookupNames = lookupField.get(spm) as MutableMap<String, Plugin>
        lookupNames.remove(pluginName.lowercase())
    }

    /**
     * Gets the SimplePluginManager, unwrapping Paper's wrapper if needed.
     */
    private fun unwrapSimplePluginManager(): SimplePluginManager? {
        val pm = server.pluginManager

        // Direct cast (works on some Paper versions)
        if (pm is SimplePluginManager) return pm

        // Paper wraps it — search for a SimplePluginManager field
        try {
            for (field in pm.javaClass.declaredFields) {
                field.isAccessible = true
                val value = field.get(pm)
                if (value is SimplePluginManager) return value
            }
        } catch (_: Exception) {}

        // Try superclass fields too
        try {
            var clazz: Class<*>? = pm.javaClass.superclass
            while (clazz != null && clazz != Any::class.java) {
                for (field in clazz.declaredFields) {
                    field.isAccessible = true
                    val value = field.get(pm)
                    if (value is SimplePluginManager) return value
                }
                clazz = clazz.superclass
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Checks for classloader leaks by examining WeakReferences to old classloaders.
     * Forces blue/green mode if too many consecutive leaks detected.
     */
    private fun checkForLeaks() {
        // Hint the GC to collect unreferenced classloaders
        System.gc()

        // Check which old classloaders were collected
        val stillLeaking = leakedClassLoaders.count { it.get() != null }
        val collected = leakedClassLoaders.size - stillLeaking

        // Clean up collected references
        leakedClassLoaders.removeAll { it.get() == null }

        if (stillLeaking > 0) {
            consecutiveLeaks++
            logger.warning("$stillLeaking classloader(s) not collected (leak #$consecutiveLeaks)")
            if (consecutiveLeaks >= 3) {
                logger.warning("Too many classloader leaks — hot-reload will be disabled for this session")
            }
        } else {
            consecutiveLeaks = 0
        }
    }
}

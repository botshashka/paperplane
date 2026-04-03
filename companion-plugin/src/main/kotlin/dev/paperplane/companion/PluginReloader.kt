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
    ROLLBACK_SUCCESS,
    PLUGIN_NOT_FOUND,
    LOAD_FAILED,
    ENABLE_FAILED,
    REFLECTION_ERROR,
    NMS_DETECTED,
    HEALTH_CHECK_FAILED
}

data class ReloadOutcome(
    val result: ReloadResult,
    val teardownMs: Long = 0,
    val loadMs: Long = 0,
    val totalMs: Long = 0
)

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

    fun reload(pluginName: String, jarFile: File, rollbackJar: File? = null): ReloadOutcome {
        val totalStart = System.currentTimeMillis()

        if (shouldForceBlueGreen) {
            logger.warning("Hot-reload disabled: $consecutiveLeaks consecutive classloader leaks detected")
            return ReloadOutcome(ReloadResult.REFLECTION_ERROR)
        }

        val oldPlugin = server.pluginManager.getPlugin(pluginName)
            ?: return ReloadOutcome(ReloadResult.PLUGIN_NOT_FOUND)

        // --- NMS detection: check if plugin uses internal server classes ---
        if (usesNmsClasses(oldPlugin)) {
            logger.warning("Plugin '$pluginName' uses NMS/CraftBukkit classes — skipping hot-reload")
            return ReloadOutcome(ReloadResult.NMS_DETECTED)
        }

        val oldClassLoader = oldPlugin.javaClass.classLoader

        // === Phase A: Teardown ===
        val teardownStart = System.currentTimeMillis()
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
            return ReloadOutcome(ReloadResult.REFLECTION_ERROR)
        }
        val teardownMs = System.currentTimeMillis() - teardownStart
        if (teardownMs > 3000) {
            logger.warning("Plugin teardown took ${teardownMs}ms (>3s) — plugin's onDisable() may be slow")
        }

        // Track classloader for leak detection
        val classLoaderRef = WeakReference(oldClassLoader)
        leakedClassLoaders.add(classLoaderRef)

        // === Phase B: Load new plugin (with rollback on failure) ===
        val loadStart = System.currentTimeMillis()
        val newPlugin: Plugin
        try {
            newPlugin = loadAndEnable(jarFile, pluginName)
        } catch (e: Exception) {
            logger.severe("Failed to load plugin from ${jarFile.name}: ${e.message}")
            if (e.cause != null) logger.severe("Caused by: ${e.cause}")
            e.printStackTrace()

            // Attempt rollback if we have the original jar
            if (rollbackJar != null && rollbackJar.exists() && rollbackJar.absolutePath != jarFile.absolutePath) {
                logger.warning("Attempting rollback from ${rollbackJar.name}...")
                try {
                    loadAndEnable(rollbackJar, pluginName)
                    val totalMs = System.currentTimeMillis() - totalStart
                    logger.info("Rollback successful — old plugin restored")
                    return ReloadOutcome(ReloadResult.ROLLBACK_SUCCESS, teardownMs, System.currentTimeMillis() - loadStart, totalMs)
                } catch (rollbackEx: Exception) {
                    logger.severe("Rollback also failed: ${rollbackEx.message}")
                    rollbackEx.printStackTrace()
                }
            }

            val totalMs = System.currentTimeMillis() - totalStart
            return ReloadOutcome(ReloadResult.LOAD_FAILED, teardownMs, System.currentTimeMillis() - loadStart, totalMs)
        }
        val loadMs = System.currentTimeMillis() - loadStart
        if (loadMs > 3000) {
            logger.warning("Plugin load+enable took ${loadMs}ms (>3s) — plugin's onEnable() may be slow")
        }

        // === Phase C: Health verification ===
        // Verify the plugin is actually enabled
        // Note: getPlugin() may return the old instance from Paper's internal registry
        // (separate from SimplePluginManager). Check newPlugin directly.
        if (!newPlugin.isEnabled) {
            logger.severe("Health check failed: plugin '$pluginName' is not enabled after reload")
            val totalMs = System.currentTimeMillis() - totalStart
            return ReloadOutcome(ReloadResult.HEALTH_CHECK_FAILED, teardownMs, loadMs, totalMs)
        }

        // Update Paper's internal plugin registry to point to the new instance
        updatePaperPluginRegistry(pluginName, newPlugin)

        // Check for classloader leaks from previous reloads
        checkForLeaks()

        val totalMs = System.currentTimeMillis() - totalStart
        logger.info("Successfully hot-reloaded '$pluginName'")
        return ReloadOutcome(ReloadResult.SUCCESS, teardownMs, loadMs, totalMs)
    }

    /**
     * Loads a plugin from a JAR file, initializes it via reflection, registers it
     * in SimplePluginManager, and enables it. Returns the enabled plugin or throws.
     */
    private fun loadAndEnable(jarFile: File, pluginName: String): Plugin {
        // Paper 1.21+ broke SimplePluginManager.loadPlugin() and JavaPluginLoader.loadPlugin().
        // JavaPlugin's constructor also checks (classLoader instanceof PluginClassLoader).
        // We must create a PluginClassLoader via reflection — it handles everything internally:
        // reading plugin.yml, creating the classloader, loading the main class, calling init().

        // Read plugin.yml for description
        val jar = JarFile(jarFile)
        val pluginYmlEntry = jar.getJarEntry("plugin.yml")
            ?: throw IllegalStateException("No plugin.yml found in ${jarFile.name}")
        val description = PluginDescriptionFile(jar.getInputStream(pluginYmlEntry))
        jar.close()

        val dataFolder = File(jarFile.parentFile, description.name)
        val loader = JavaPluginLoader(server)

        // Create PluginClassLoader via reflection — it instantiates the plugin and calls init()
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
            } catch (e: Exception) {
                logger.fine("Field '$fieldName' not found on PluginClassLoader: ${e.message}")
            }
        }

        if (pluginInstance == null) {
            // Constructor didn't create the plugin — do it manually
            val mainClass = (classLoaderInstance as ClassLoader).loadClass(description.main)
            pluginInstance = mainClass.getDeclaredConstructor().newInstance() as JavaPlugin
        }

        // Ensure JavaPlugin.init() has been called with a valid loader
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
        } catch (initEx: Exception) {
            // init() might not exist in this Paper version — try setting fields directly
            logger.warning("JavaPlugin.init() unavailable, falling back to field injection: ${initEx.message}")
            val criticalFields = setOf("server", "description", "classLoader")
            val setFields = mutableSetOf<String>()
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
                    setFields.add(name)
                } catch (e: Exception) {
                    logger.warning("Failed to set field '$name': ${e.message}")
                }
            }
            // Also try PluginBase fields
            try {
                val loaderF = Class.forName("org.bukkit.plugin.PluginBase").getDeclaredField("loader")
                loaderF.isAccessible = true
                loaderF.set(pluginInstance, loader)
            } catch (e: Exception) {
                logger.warning("Failed to set PluginBase.loader: ${e.message}")
            }
            // Fail if critical fields weren't set
            val missing = criticalFields - setFields
            if (missing.isNotEmpty()) {
                throw IllegalStateException("Critical fields not set after fallback injection: $missing")
            }
        }

        // Register in SimplePluginManager's internal lists
        val spm = unwrapSimplePluginManager()
        if (spm != null) {
            val pluginsField = SimplePluginManager::class.java.getDeclaredField("plugins")
            pluginsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val plugins = pluginsField.get(spm) as MutableList<Plugin>
            plugins.add(pluginInstance)

            val lookupField = SimplePluginManager::class.java.getDeclaredField("lookupNames")
            lookupField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val lookupNames = lookupField.get(spm) as MutableMap<String, Plugin>
            lookupNames[pluginName.lowercase()] = pluginInstance
        }

        // Register plugin.yml commands into the command map (normally done by loadPlugin, which we bypass)
        registerCommands(pluginInstance, description)

        // Enable new plugin (calls onEnable via the plugin's loader)
        server.pluginManager.enablePlugin(pluginInstance)

        // Sync Brigadier command tree so tab-completion works for newly registered commands
        try {
            server.javaClass.getMethod("syncCommands").invoke(server)
        } catch (_: Exception) {}

        return pluginInstance
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
     * Registers commands from plugin.yml into the server's command map.
     * Normally SimplePluginManager.loadPlugin() does this, but we bypass it.
     */
    private fun registerCommands(plugin: JavaPlugin, description: PluginDescriptionFile) {
        try {
            val commandMap = server.commandMap
            for ((name, cmdInfo) in description.commands) {
                val command = org.bukkit.command.PluginCommand::class.java
                    .getDeclaredConstructor(String::class.java, Plugin::class.java)
                    .apply { isAccessible = true }
                    .newInstance(name, plugin)

                // Set command metadata from plugin.yml
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
        val pluginThreads = Thread.getAllStackTraces().keys
            .filter { it.contextClassLoader == classLoader && it != Thread.currentThread() }

        for (thread in pluginThreads) {
            thread.interrupt()
        }
        if (pluginThreads.isNotEmpty()) {
            logger.info("Interrupted ${pluginThreads.size} orphan thread(s) from old plugin")
        }

        // Wait for threads to actually stop
        for (thread in pluginThreads) {
            thread.join(2000)
            if (thread.isAlive) {
                val stack = thread.stackTrace.take(5).joinToString("\n    ") { it.toString() }
                logger.warning("Thread '${thread.name}' did not stop after interrupt:\n    $stack")
            }
        }
    }

    /**
     * Updates Paper's internal plugin registry (PaperPluginManagerImpl) to point to the new plugin instance.
     * Paper maintains its own plugin map separate from SimplePluginManager.
     */
    private fun updatePaperPluginRegistry(pluginName: String, newPlugin: Plugin) {
        var updated = false
        // Walk the object graph from pluginManager up to 4 levels deep, looking for
        // Lists/Maps that contain a Plugin with our name.
        val visited = mutableSetOf<Any>()
        val queue = ArrayDeque<Pair<Any, Int>>() // (object, depth)
        queue.add(server.pluginManager to 0)

        while (queue.isNotEmpty()) {
            val (obj, depth) = queue.removeFirst()
            if (depth > 4 || !visited.add(System.identityHashCode(obj))) continue
            val cls = obj.javaClass
            val cn = cls.name
            if (cn.startsWith("java.") || cn.startsWith("jdk.") ||
                cn.startsWith("sun.") || cn.startsWith("javax.") ||
                cn.startsWith("kotlin.")) continue

            // Check all fields of this object
            var currentClass: Class<*>? = cls
            while (currentClass != null && currentClass != Any::class.java) {
                for (field in currentClass.declaredFields) {
                    try {
                        field.isAccessible = true
                        val value = field.get(obj) ?: continue

                        // Check if this field holds a List containing our plugin
                        if (value is MutableList<*>) {
                            val idx = value.indexOfFirst { (it as? Plugin)?.name == pluginName }
                            if (idx >= 0) {
                                @Suppress("UNCHECKED_CAST")
                                (value as MutableList<Any>)[idx] = newPlugin
                                logger.info("Updated plugin in list: ${cls.simpleName}.${field.name}")
                                updated = true
                            }
                        }
                        // Check if this field holds a Map containing our plugin
                        if (value is MutableMap<*, *>) {
                            for (key in listOf(pluginName, pluginName.lowercase())) {
                                val existing = value[key]
                                if (existing is Plugin && existing.name == pluginName) {
                                    @Suppress("UNCHECKED_CAST")
                                    (value as MutableMap<String, Any>)[key] = newPlugin
                                    logger.info("Updated plugin in map: ${cls.simpleName}.${field.name}[$key]")
                                    updated = true
                                }
                            }
                        }
                        // Enqueue non-JDK objects for deeper traversal
                        val vcn = value.javaClass.name
                        if (!vcn.startsWith("java.") && !vcn.startsWith("jdk.") &&
                            !vcn.startsWith("sun.") && !vcn.startsWith("javax.") &&
                            !vcn.startsWith("kotlin.") && depth < 4) {
                            queue.add(value to depth + 1)
                        }
                    } catch (_: Exception) {
                        // Skip inaccessible fields
                    }
                }
                currentClass = currentClass.superclass
            }
        }
        if (!updated) {
            logger.warning("Could not find plugin '$pluginName' in Paper's internal registry")
        }
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
     * Checks for classloader leaks using deferred detection.
     * Only examines classloaders from reloads before the most recent one,
     * giving GC at least one full reload cycle to collect them.
     */
    private fun checkForLeaks() {
        // Only check classloaders from reloads BEFORE the most recent one
        // (give GC at least one full reload cycle to collect)
        if (leakedClassLoaders.size <= 1) return

        val toCheck = leakedClassLoaders.dropLast(1)
        val stillLeaking = toCheck.count { it.get() != null }

        // Clean up collected references (but keep the most recent one)
        leakedClassLoaders.removeAll { ref -> ref != leakedClassLoaders.last() && ref.get() == null }

        if (stillLeaking > 0) {
            consecutiveLeaks++
            logger.warning("$stillLeaking classloader(s) not collected after full reload cycle (leak #$consecutiveLeaks)")
            if (consecutiveLeaks >= 3) {
                logger.warning("Too many classloader leaks — hot-reload will be disabled for this session")
            }
        } else {
            consecutiveLeaks = 0
        }
    }
}

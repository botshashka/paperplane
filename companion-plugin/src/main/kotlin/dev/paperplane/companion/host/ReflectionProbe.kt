package dev.paperplane.companion.host

import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import org.bukkit.Server
import org.bukkit.help.HelpTopic
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.SimplePluginManager
import org.bukkit.plugin.java.JavaPlugin

/**
 * Resolves and caches every reflection point the host needs.
 *
 * Run once at companion `onEnable`. If any required field/method can't be found, throw
 * [UnsupportedPaperVersionException] and refuse to start — better a clear error than partial
 * functionality.
 *
 * **The host's entire reflection footprint lives here.** Three reflection points:
 *
 * 1. **JavaPlugin init** — try the package-private
 *    `JavaPlugin.init(PluginLoader, Server, PluginDescriptionFile, File, File, ClassLoader)` first;
 *    fall back to direct private-field injection (`server`, `description`, `dataFolder`, `file`,
 *    `classLoader`). The init method is the designated init path; field injection is the safety
 *    net for hypothetical future Paper versions that rename or remove init.
 *    Spigot/Paper source: <https://hub.spigotmc.org/stash/projects/SPIGOT/repos/bukkit/browse/src/main/java/org/bukkit/plugin/java/JavaPlugin.java>
 *
 * 2. **SimplePluginManager.lookupNames** — `Map<String, Plugin>` consulted by Paper's
 *    `PaperPluginManagerImpl.getPlugin(name)` for cross-plugin dependency lookups. Symmetric
 *    add/remove on every host load/unload.
 *    Bukkit source: <https://hub.spigotmc.org/stash/projects/SPIGOT/repos/bukkit/browse/src/main/java/org/bukkit/plugin/SimplePluginManager.java>
 *
 * 3. **SimpleHelpMap.helpTopics** — `Map<String, HelpTopic>` consulted by `/help`. Paper's
 *    `HelpMap.initializeCommands()` runs once during `enablePlugins(POSTWORLD)`, strictly before
 *    the companion's first scheduler tick fires the host load, so inner-plugin commands miss the
 *    one-shot pass. Public `HelpMap.addTopic` skips existing keys and there is no `removeTopic`,
 *    so direct map mutation is the only way to keep `/help` in sync across hot-reload. Resolved
 *    by generic signature (`Map<String, HelpTopic>`), not field name — survives a rename.
 *    CraftBukkit source: <https://github.com/PaperMC/Paper/blob/main/paper-server/src/main/java/org/bukkit/craftbukkit/help/SimpleHelpMap.java>
 */
class ReflectionProbe
private constructor(
    val javaPluginInit: Method?,
    val javaPluginFields: JavaPluginFields?,
    val spmLookupNamesField: Field,
    val helpTopics: MutableMap<String, HelpTopic>,
) {
  init {
    require(javaPluginInit != null || javaPluginFields != null) {
      "Probe accepted with neither init method nor field-injection fallback resolved."
    }
  }

  companion object {
    /**
     * Resolves all reflection targets. Throws [UnsupportedPaperVersionException] if the host
     * cannot proceed.
     */
    fun probe(server: Server): ReflectionProbe {
      val errors = mutableListOf<String>()

      val initMethod = resolveInitMethod()
      val fields = resolveJavaPluginFields()
      if (initMethod == null && fields == null) {
        errors.add(
            "Neither JavaPlugin.init(...) nor private fields (server, description, dataFolder, file, classLoader) were resolvable. " +
                "Host cannot construct an inner plugin without one of these paths.")
      }

      val lookupField =
          resolveSpmLookupNamesField(server)
              ?: run {
                errors.add(
                    "SimplePluginManager.lookupNames field not found. " +
                        "Host cannot register the inner plugin for cross-plugin name lookups.")
                null
              }

      val helpTopics =
          resolveHelpTopicsMap(server)
              ?: run {
                errors.add(
                    "SimpleHelpMap.helpTopics field (Map<String, HelpTopic>) not found on " +
                        "${server.helpMap.javaClass.name}. Host cannot register help topics for " +
                        "inner-plugin commands.")
                null
              }

      if (errors.isNotEmpty() || lookupField == null || helpTopics == null) {
        throw UnsupportedPaperVersionException(buildMessage(server, errors))
      }

      return ReflectionProbe(initMethod, fields, lookupField, helpTopics)
    }

    private fun resolveInitMethod(): Method? =
        try {
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
              .apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
          null
        }

    private fun resolveJavaPluginFields(): JavaPluginFields? {
      val server = field(JavaPlugin::class.java, "server") ?: return null
      val description = field(JavaPlugin::class.java, "description") ?: return null
      val dataFolder = field(JavaPlugin::class.java, "dataFolder") ?: return null
      val file = field(JavaPlugin::class.java, "file") ?: return null
      val classLoader = field(JavaPlugin::class.java, "classLoader") ?: return null
      return JavaPluginFields(server, description, dataFolder, file, classLoader)
    }

    private fun resolveSpmLookupNamesField(server: Server): Field? {
      val spm = unwrapSpm(server.pluginManager) ?: return null
      return field(spm.javaClass, "lookupNames")
    }

    /**
     * Locates the `Map<String, HelpTopic>` field on the runtime [org.bukkit.help.HelpMap]
     * implementation by **generic signature**, not field name, and returns the live map instance.
     * Paper's `SimpleHelpMap.helpTopics` has been that exact name and type since 2012, but a future
     * rename would silently break `/help` integration if we keyed on the name. Walking declared
     * fields and matching on the parameterized type also lets the same resolver work against
     * MockBukkit's `HelpMapMock`, which calls its field `topics` instead.
     *
     * Returns the first matching field's value. If `SimpleHelpMap` ever declares a second
     * `Map<String, HelpTopic>` field, the first one declared wins — acceptable failure mode
     * because the probe-pinning tests would catch the ambiguity at our next dependency bump.
     */
    internal fun resolveHelpTopicsMap(server: Server): MutableMap<String, HelpTopic>? {
      val helpMap = server.helpMap
      for (f in helpMap.javaClass.declaredFields) {
        val generic = f.genericType as? ParameterizedType ?: continue
        val raw = generic.rawType as? Class<*> ?: continue
        if (!Map::class.java.isAssignableFrom(raw)) continue
        val args = generic.actualTypeArguments
        if (args.size == 2 && args[0] == String::class.java && args[1] == HelpTopic::class.java) {
          f.isAccessible = true
          @Suppress("UNCHECKED_CAST")
          return f.get(helpMap) as MutableMap<String, HelpTopic>
        }
      }
      return null
    }

    /**
     * Modern Paper wraps `SimplePluginManager` inside `PaperPluginManagerImpl`. The wrapper
     * delegates name lookups to SPM, so SPM is the canonical write target. Walk one level of
     * fields to find the nested SPM if the manager isn't already SPM.
     */
    internal fun unwrapSpm(pm: PluginManager): SimplePluginManager? {
      if (pm is SimplePluginManager) return pm
      var clazz: Class<*>? = pm.javaClass
      while (clazz != null && clazz != Any::class.java) {
        for (f in clazz.declaredFields) {
          try {
            f.isAccessible = true
            val value = f.get(pm)
            if (value is SimplePluginManager) return value
          } catch (_: ReflectiveOperationException) {
            // Skip inaccessible / null fields.
          }
        }
        clazz = clazz.superclass
      }
      return null
    }

    private fun field(owner: Class<*>, name: String): Field? =
        try {
          owner.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
          null
        }

    private fun buildMessage(server: Server, errors: List<String>): String {
      val paperVersion =
          try {
            server.javaClass.`package`?.implementationVersion ?: "unknown"
          } catch (
              @Suppress("TooGenericExceptionCaught") // Server may not be initialized yet.
              _: Exception) {
            "unknown"
          }
      return buildString {
        appendLine("Unsupported Paper version (Paper $paperVersion). PaperPlane needs to be updated.")
        appendLine("Please open an issue at https://github.com/botshashka/paperplane")
        appendLine()
        appendLine("Probe failures:")
        for (e in errors) appendLine("  - $e")
      }
    }
  }

  data class JavaPluginFields(
      val server: Field,
      val description: Field,
      val dataFolder: Field,
      val file: Field,
      val classLoader: Field,
  )
}

class UnsupportedPaperVersionException(message: String) : RuntimeException(message)

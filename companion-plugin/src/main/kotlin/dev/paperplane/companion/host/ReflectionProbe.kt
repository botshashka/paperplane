package dev.paperplane.companion.host

import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.bukkit.Server
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
 * **The host's entire reflection footprint lives here.** Two reflection points:
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
 */
class ReflectionProbe
private constructor(
    val javaPluginInit: Method?,
    val javaPluginFields: JavaPluginFields?,
    val spmLookupNamesField: Field,
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

      if (errors.isNotEmpty() || lookupField == null) {
        throw UnsupportedPaperVersionException(buildMessage(server, errors))
      }

      return ReflectionProbe(initMethod, fields, lookupField)
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

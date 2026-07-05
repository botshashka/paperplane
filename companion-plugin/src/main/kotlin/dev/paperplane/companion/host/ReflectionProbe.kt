package dev.paperplane.companion.host

import dev.paperplane.companion.DevPluginClassLoader
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import org.bukkit.Server
import org.bukkit.help.HelpTopic
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.SimplePluginManager
import org.bukkit.plugin.java.JavaPlugin

/**
 * Resolves and caches every reflection point the host needs, and verifies the runtime supports the
 * plugin-loader API that dev-mode loading is built on.
 *
 * Run once at first host use. If any required guard fails, throw [UnsupportedPaperVersionException]
 * and refuse to load — better a clear, actionable error than partial functionality.
 *
 * **The host's entire reflection footprint lives here.** Two live reflection points plus a set of
 * compatibility guards:
 *
 * 1. **ConfiguredPluginClassLoader integration guard** — dev-mode loading relies on Paper's
 *    `JavaPlugin` no-arg ctor calling back into a `ConfiguredPluginClassLoader` (implemented by
 *    [DevPluginClassLoader]) to initialize the plugin. This interface exists only on Paper 1.19.3+.
 *    The guard confirms the interface, the `PluginMeta` type, the 7-arg `JavaPlugin.init` the
 *    loader invokes, and that [DevPluginClassLoader] concretely implements every interface method
 *    (so a future Paper adding a method surfaces as a clear probe failure, not an
 *    AbstractMethodError at load time).
 *
 * 2. **SimplePluginManager.lookupNames** — `Map<String, Plugin>` consulted by Paper's
 *    `PaperPluginManagerImpl.getPlugin(name)` for cross-plugin dependency lookups. Symmetric
 *    add/remove on every host load/unload.
 *
 * 3. **SimpleHelpMap.helpTopics** — `Map<String, HelpTopic>` consulted by `/help`. Paper's
 *    `HelpMap.initializeCommands()` runs once during `enablePlugins(POSTWORLD)`, strictly before
 *    the companion's first scheduler tick fires the host load, so inner-plugin commands miss the
 *    one-shot pass. Public `HelpMap.addTopic` skips existing keys and there is no `removeTopic`, so
 *    direct map mutation is the only way to keep `/help` in sync across hot-reload. Resolved by
 *    generic signature (`Map<String, HelpTopic>`), not field name — survives a rename.
 */
class ReflectionProbe
private constructor(
    val spmLookupNamesField: Field,
    val helpTopics: MutableMap<String, HelpTopic>,
) {

  companion object {
    private const val CPCL_INTERFACE =
        "io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader"
    private const val PLUGIN_META = "io.papermc.paper.plugin.configuration.PluginMeta"

    private const val VERSION_FLOOR_MESSAGE =
        "This Paper version predates the plugin-loader API (needs Paper 1.19.3+). Hot-reload is " +
            "unavailable — set `dev.mode: restart` or `blue-green` in paperplane.yml."

    /**
     * Resolves all reflection targets and runs the compatibility guards. Throws
     * [UnsupportedPaperVersionException] if the host cannot proceed.
     */
    fun probe(server: Server): ReflectionProbe {
      val errors = mutableListOf<String>()

      // CPCL guard runs FIRST — nothing may touch DevPluginClassLoader::class.java until (a) below
      // confirms the interface exists on this runtime.
      runCpclGuard(errors)

      val lookupField =
          resolveSpmLookupNamesField(server)
              ?: run {
                errors.add(
                    "SimplePluginManager.lookupNames field not found. " +
                        "Host cannot register the inner plugin for cross-plugin name lookups."
                )
                null
              }

      val helpTopics =
          resolveHelpTopicsMap(server)
              ?: run {
                errors.add(
                    "SimpleHelpMap.helpTopics field (Map<String, HelpTopic>) not found on " +
                        "${server.helpMap.javaClass.name}. Host cannot register help topics for " +
                        "inner-plugin commands."
                )
                null
              }

      if (errors.isNotEmpty() || lookupField == null || helpTopics == null) {
        throw UnsupportedPaperVersionException(buildMessage(server, errors))
      }

      return ReflectionProbe(lookupField, helpTopics)
    }

    /**
     * Verifies the ConfiguredPluginClassLoader integration this host is built on. Steps are
     * ordered: (a) the interface, (b) the PluginMeta type, (c) the 7-arg `JavaPlugin.init` the
     * loader calls, (d) that [DevPluginClassLoader] concretely implements every interface method.
     * Steps (c)/(d) only run once (a)/(b) resolve, so [DevPluginClassLoader] is never touched on a
     * pre-1.19.3 runtime where loading it would `NoClassDefFoundError`.
     */
    private fun runCpclGuard(errors: MutableList<String>) {
      val loader = JavaPlugin::class.java.classLoader

      // (a) The ConfiguredPluginClassLoader interface itself. Catch Throwable — a missing
      // transitive type surfaces as NoClassDefFoundError, not ClassNotFoundException.
      val cpclInterface =
          try {
            Class.forName(CPCL_INTERFACE, false, loader)
          } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
            errors.add(VERSION_FLOOR_MESSAGE)
            return
          }

      // (b) The PluginMeta type the 7-arg init consumes.
      val metaClass =
          try {
            Class.forName(PLUGIN_META, false, loader)
          } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
            errors.add(VERSION_FLOOR_MESSAGE)
            return
          }

      // (c) The exact JavaPlugin.init(...) overload the loader's init(plugin) invokes.
      try {
        JavaPlugin::class
            .java
            .getMethod(
                "init",
                Server::class.java,
                PluginDescriptionFile::class.java,
                File::class.java,
                File::class.java,
                ClassLoader::class.java,
                metaClass,
                java.util.logging.Logger::class.java,
            )
      } catch (_: NoSuchMethodException) {
        errors.add(
            "JavaPlugin.init(Server, PluginDescriptionFile, File, File, ClassLoader, PluginMeta, " +
                "Logger) not found — the plugin-loader init contract changed. $VERSION_FLOOR_MESSAGE"
        )
        return
      }

      // (d) DevPluginClassLoader must concretely implement every method of the runtime interface.
      // Guards against a future Paper adding an interface method we don't override (which would
      // otherwise throw AbstractMethodError at load time — this turns it into a clear probe error).
      for (m in cpclInterface.methods) {
        val impl =
            try {
              DevPluginClassLoader::class.java.getMethod(m.name, *m.parameterTypes)
            } catch (_: NoSuchMethodException) {
              null
            }
        if (impl == null || Modifier.isAbstract(impl.modifiers)) {
          errors.add(
              "DevPluginClassLoader does not implement ConfiguredPluginClassLoader.${m.name}(...) " +
                  "— the plugin-loader interface gained a method PaperPlane must override. " +
                  "PaperPlane needs to be updated."
          )
        }
      }
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
     * `Map<String, HelpTopic>` field, the first one declared wins — acceptable failure mode because
     * the probe-pinning tests would catch the ambiguity at our next dependency bump.
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
     * delegates name lookups to SPM, so SPM is the canonical write target. Walk one level of fields
     * to find the nested SPM if the manager isn't already SPM.
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
        appendLine(
            "Unsupported Paper version (Paper $paperVersion). PaperPlane needs to be updated."
        )
        appendLine("Please open an issue at https://github.com/botshashka/paperplane")
        appendLine()
        appendLine("Probe failures:")
        for (e in errors) appendLine("  - $e")
      }
    }
  }
}

class UnsupportedPaperVersionException(message: String) : RuntimeException(message)

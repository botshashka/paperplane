package dev.paperplane.companion.host

import dev.paperplane.companion.DevPluginClassLoader
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import org.bukkit.Server
import org.bukkit.help.HelpTopic
import org.bukkit.plugin.Plugin
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
    /**
     * The lookup map Paper's modern plugin manager ACTUALLY consults for `getPlugin(name)`, or
     * `null` when no Paper manager is present (unit tests, legacy runtimes) and SPM's own map is
     * authoritative. Verified empirically on Paper 1.21.11: writing only SPM's `lookupNames` leaves
     * cross-plugin `getPlugin(name)` returning null, because `PaperPluginManagerImpl`'s instance
     * manager keeps its own map.
     */
    val paperLookupNames: MutableMap<String, Plugin>?,
    val helpTopics: MutableMap<String, HelpTopic>,
    /**
     * Fires Paper's `LifecycleEvents.COMMANDS` re-collection the way `/minecraft:reload` does, or
     * `null` when this runtime has no Brigadier lifecycle-command machinery to drive (pre-1.20.6
     * Paper, unit-test fakes). Null is NOT an error: plugins that don't use lifecycle commands are
     * completely unaffected, and on runtimes without the API there is nothing to re-collect. A
     * runtime that HAS the API but whose internals don't match what we resolve is an error — see
     * [resolveLifecycleCommandSync].
     */
    val lifecycleCommandSync: LifecycleCommandSync?,
) {

  companion object {
    private const val CPCL_INTERFACE =
        "io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader"
    private const val PLUGIN_META = "io.papermc.paper.plugin.configuration.PluginMeta"

    // Brigadier lifecycle-command re-collection touchpoints. The first two are paper-api types;
    // the runner and registrar are paper-server internals (verified via javap on the shipped
    // 1.21.4 and 1.21.11 server jars — identical shapes).
    private const val LIFECYCLE_EVENTS =
        "io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents"
    private const val RELOADABLE_CAUSE =
        "io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent\$Cause"
    private const val LIFECYCLE_RUNNER =
        "io.papermc.paper.plugin.lifecycle.event.LifecycleEventRunner"
    private const val PAPER_COMMANDS = "io.papermc.paper.command.brigadier.PaperCommands"

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

      val paperLookupNames = resolvePaperLookupNames(server, errors)

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

      val lifecycleCommandSync = resolveLifecycleCommandSync(server, errors)

      if (errors.isNotEmpty() || lookupField == null || helpTopics == null) {
        throw UnsupportedPaperVersionException(buildMessage(server, errors))
      }

      return ReflectionProbe(lookupField, paperLookupNames, helpTopics, lifecycleCommandSync)
    }

    /**
     * Resolves the internals behind Paper's `/minecraft:reload` lifecycle-command re-collection:
     * `LifecycleEventRunner.INSTANCE.callReloadableRegistrarEvent(...)` plus
     * `PaperCommands.INSTANCE.setValid()` (both `paper-server` classes), and the API-side
     * `LifecycleEvents.COMMANDS` event type and `ReloadableRegistrarEvent.Cause.RELOAD` arguments.
     *
     * Returns `null` without error when the runtime has no Brigadier lifecycle-command API at all:
     * no `LifecycleEvents` class or no `COMMANDS` field (Paper < 1.20.6 — older than the API), or
     * no `syncCommands` method on the server class (not a CraftServer — unit-test fakes). In both
     * cases there is nothing to re-collect and plugins without lifecycle commands are unaffected.
     *
     * Once the runtime demonstrably HAS the API (`COMMANDS` resolvable AND a CraftServer-shaped
     * server), every internal must resolve — a failure means Paper moved the re-collection
     * machinery, and silently skipping it would make dev-loaded plugins' Brigadier commands never
     * register (and leave stale nodes executing old-classloader code across hot-reloads). That is a
     * correctness hole, so it is a probe error, not a degraded mode.
     */
    internal fun resolveLifecycleCommandSync(
        server: Server,
        errors: MutableList<String>,
    ): LifecycleCommandSync? {
      val apiLoader = JavaPlugin::class.java.classLoader
      val commandsEventType =
          try {
            Class.forName(LIFECYCLE_EVENTS, false, apiLoader).getField("COMMANDS").get(null)
          } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
            return null // API predates lifecycle commands — nothing to re-collect.
          } ?: return null
      val isCraftServer =
          try {
            server.javaClass.getMethod("syncCommands")
            true
          } catch (_: NoSuchMethodException) {
            false
          }
      if (!isCraftServer) return null // Unit-test fake — no server-side machinery to drive.

      fun failed(what: String): Nothing? {
        errors.add(
            "Paper's Brigadier lifecycle-command internals changed: $what. Host cannot re-fire " +
                "LifecycleEvents.COMMANDS, so dev-loaded plugins' Brigadier commands would never " +
                "register."
        )
        return null
      }

      val serverLoader = server.javaClass.classLoader
      val runnerClass =
          try {
            Class.forName(LIFECYCLE_RUNNER, false, serverLoader)
          } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
            return failed("$LIFECYCLE_RUNNER not found")
          }
      val runner =
          try {
            runnerClass.getField("INSTANCE").get(null)
          } catch (_: ReflectiveOperationException) {
            return failed("$LIFECYCLE_RUNNER.INSTANCE not found")
          } ?: return failed("$LIFECYCLE_RUNNER.INSTANCE is null")
      val callMethods =
          runnerClass.methods.filter {
            it.name == "callReloadableRegistrarEvent" && it.parameterCount == 4
          }
      val callMethod =
          callMethods.singleOrNull()
              ?: return failed(
                  "expected exactly one 4-arg LifecycleEventRunner.callReloadableRegistrarEvent, " +
                      "found ${callMethods.size}"
              )
      val paperCommandsClass =
          try {
            Class.forName(PAPER_COMMANDS, false, serverLoader)
          } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
            return failed("$PAPER_COMMANDS not found")
          }
      val paperCommands =
          try {
            paperCommandsClass.getField("INSTANCE").get(null)
          } catch (_: ReflectiveOperationException) {
            return failed("$PAPER_COMMANDS.INSTANCE not found")
          } ?: return failed("$PAPER_COMMANDS.INSTANCE is null")
      val setValid =
          try {
            paperCommandsClass.getMethod("setValid")
          } catch (_: NoSuchMethodException) {
            return failed("$PAPER_COMMANDS.setValid() not found")
          }
      val reloadCause =
          try {
            Class.forName(RELOADABLE_CAUSE, false, apiLoader).getField("RELOAD").get(null)
          } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
            return failed("$RELOADABLE_CAUSE.RELOAD not found")
          } ?: return failed("$RELOADABLE_CAUSE.RELOAD is null")

      // Pin that the resolved method actually accepts the arguments we will pass — a signature
      // drift must surface here as a clear probe error, not as an IllegalArgumentException later.
      val params = callMethod.parameterTypes
      val args = listOf(commandsEventType, paperCommands, Plugin::class.java, reloadCause)
      for ((i, arg) in args.withIndex()) {
        if (!params[i].isInstance(arg)) {
          return failed(
              "callReloadableRegistrarEvent parameter $i (${params[i].name}) does not accept " +
                  arg.javaClass.name
          )
        }
      }

      return LifecycleCommandSync(
          runner,
          callMethod,
          paperCommands,
          setValid,
          commandsEventType,
          reloadCause,
      )
    }

    /**
     * Locates the lookup map Paper's modern plugin manager consults for `getPlugin(name)`.
     *
     * On modern Paper, `SimplePluginManager` delegates to the object in its `paperPluginManager`
     * field (`PaperPluginManagerImpl`), whose instance manager keeps its OWN `lookupNames` map —
     * writes to SPM's map are invisible to `getPlugin(name)`. The map is resolved by generic
     * signature (`Map<String, Plugin>`), first on the Paper manager itself, then one field level
     * deeper (it lives on `PaperPluginInstanceManager` on 1.21.x).
     *
     * Returns `null` without error when no Paper manager exists (unit tests, legacy runtimes) —
     * SPM's map is authoritative there. Adds an error when a Paper manager IS present but its map
     * can't be found: that means Paper's internals changed and silent SPM-only registration would
     * break cross-plugin lookups.
     */
    internal fun resolvePaperLookupNames(
        server: Server,
        errors: MutableList<String>,
    ): MutableMap<String, Plugin>? {
      // Without an SPM the probe fails hard on the lookupNames point anyway — nothing to resolve.
      val spm = unwrapSpm(server.pluginManager) ?: return null
      val paperManager: Any? =
          try {
            SimplePluginManager::class
                .java
                .getDeclaredField("paperPluginManager")
                .apply { isAccessible = true }
                .get(spm)
          } catch (_: NoSuchFieldException) {
            null
          }
      if (paperManager == null) return null

      findStringPluginMap(paperManager)?.let {
        return it
      }
      // One level deeper: PaperPluginManagerImpl keeps the map on its instance manager. Skip any
      // SimplePluginManager reference — finding SPM's own map here would silently restore the
      // exact bug this resolver exists to fix.
      var clazz: Class<*>? = paperManager.javaClass
      while (clazz != null && clazz != Any::class.java) {
        for (f in clazz.declaredFields) {
          if (f.type.isPrimitive) continue
          val value =
              try {
                f.isAccessible = true
                f.get(paperManager)
              } catch (_: ReflectiveOperationException) {
                null
              } ?: continue
          if (value is SimplePluginManager) continue
          findStringPluginMap(value)?.let {
            return it
          }
        }
        clazz = clazz.superclass
      }

      errors.add(
          "Paper's plugin manager (${paperManager.javaClass.name}) exposes no Map<String, Plugin> " +
              "lookup field. Host cannot register the inner plugin for cross-plugin getPlugin(name)."
      )
      return null
    }

    /** Finds the first `Map<String, Plugin>`-typed field on [owner] by generic signature. */
    private fun findStringPluginMap(owner: Any): MutableMap<String, Plugin>? {
      var clazz: Class<*>? = owner.javaClass
      while (clazz != null && clazz != Any::class.java) {
        for (f in clazz.declaredFields) {
          val generic = f.genericType as? ParameterizedType ?: continue
          val raw = generic.rawType as? Class<*> ?: continue
          if (!Map::class.java.isAssignableFrom(raw)) continue
          val args = generic.actualTypeArguments
          if (args.size == 2 && args[0] == String::class.java && args[1] == Plugin::class.java) {
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return f.get(owner) as? MutableMap<String, Plugin>
          }
        }
        clazz = clazz.superclass
      }
      return null
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

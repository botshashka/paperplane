package dev.paperplane.companion.host

import java.lang.reflect.Constructor
import java.util.logging.Logger
import org.bukkit.Server
import org.bukkit.command.PluginCommand
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.java.JavaPlugin

/**
 * Diff-based command registration via the public `CommandMap` API.
 *
 * On each `apply()`, computes the delta between currently-registered commands and the description
 * passed in:
 * - Commands in description but not registered → newly construct + register.
 * - Commands registered but not in description → unregister.
 * - Commands present in both → leave as-is (avoid churn).
 *
 * Tracks state per [JavaPlugin] instance because reload swaps the plugin reference. The previous
 * plugin's commands are torn down via [clear] before the new plugin's [apply] runs.
 *
 * `PluginCommand`'s constructor is package-private to `org.bukkit.command`. A previous attempt
 * dodged reflection by placing a helper class in that package for compile-time access, but Paper's
 * `PluginClassLoader` refuses to load `org.bukkit.*` classes from plugin JARs — see
 * [dev.paperplane.companion.ProtectedPackagesTest]. The standard escape hatch every Bukkit plugin
 * framework uses is a cached reflective constructor; we do the same.
 */
class CommandRegistrar(private val server: Server, private val logger: Logger) {

  companion object {
    private val pluginCommandCtor: Constructor<PluginCommand> =
        PluginCommand::class
            .java
            .getDeclaredConstructor(String::class.java, Plugin::class.java)
            .apply { isAccessible = true }
  }

  /** Lowercase command name → registered command. Source of truth for the diff. */
  private val applied = mutableMapOf<String, PluginCommand>()

  /**
   * Apply the description's `commands:` block. Diff against [applied], register additions,
   * unregister removals.
   */
  fun apply(plugin: JavaPlugin, description: PluginDescriptionFile) {
    val desired = parseCommands(description)

    val toRemove = applied.keys - desired.keys
    for (name in toRemove) {
      val cmd = applied.remove(name) ?: continue
      unregisterFully(cmd)
    }

    for ((name, spec) in desired) {
      if (applied.containsKey(name)) continue // already registered, no churn
      val cmd = pluginCommandCtor.newInstance(name, plugin)
      configureCommand(cmd, spec)
      val registered = server.commandMap.register(description.name, cmd)
      if (!registered) {
        logger.warning(
            "Command '$name' could not be registered under '${description.name}' fallback prefix.")
      }
      applied[name] = cmd
    }

    BrigadierSync.sync(server)
  }

  /** Tear down all commands registered for [plugin]. Called during reload teardown / shutdown. */
  fun clear() {
    for ((_, cmd) in applied) {
      unregisterFully(cmd)
    }
    applied.clear()
    BrigadierSync.sync(server)
  }

  /**
   * `Command.unregister(CommandMap)` only flips internal state; it does NOT remove the command
   * from the command map's known-commands lookup. Without removing the entries, the next call to
   * `CommandMap.getCommand(name)` still returns the torn-down command. Walk the map and drop any
   * entries pointing at our [cmd] (covers aliases and the `pluginName:` prefix variants both).
   */
  private fun unregisterFully(cmd: PluginCommand) {
    cmd.unregister(server.commandMap)
    val known = server.commandMap.knownCommands
    val keys = known.entries.filter { it.value === cmd }.map { it.key }
    for (k in keys) known.remove(k)
  }

  /** Snapshot of currently-registered command names. For tests and diagnostics. */
  fun registered(): Set<String> = applied.keys.toSet()

  private fun configureCommand(cmd: PluginCommand, spec: CommandSpec) {
    spec.description?.let { cmd.description = it }
    spec.usage?.let { cmd.usage = it }
    spec.permission?.let { cmd.permission = it }
    spec.permissionMessage?.let { cmd.permissionMessage = it }
    if (spec.aliases.isNotEmpty()) cmd.aliases = spec.aliases
  }

  private fun parseCommands(description: PluginDescriptionFile): Map<String, CommandSpec> {
    val result = LinkedHashMap<String, CommandSpec>()
    for ((rawName, raw) in description.commands) {
      val name = rawName.lowercase()
      val map = raw as? Map<*, *>
      result[name] =
          CommandSpec(
              description = (map?.get("description") as? String),
              usage = (map?.get("usage") as? String),
              permission = (map?.get("permission") as? String),
              permissionMessage = (map?.get("permission-message") as? String),
              aliases =
                  when (val a = map?.get("aliases")) {
                    is List<*> -> a.filterIsInstance<String>()
                    is String -> listOf(a)
                    else -> emptyList()
                  },
          )
    }
    return result
  }

  private data class CommandSpec(
      val description: String?,
      val usage: String?,
      val permission: String?,
      val permissionMessage: String?,
      val aliases: List<String>,
  )
}

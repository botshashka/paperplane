package org.bukkit.command

import org.bukkit.plugin.Plugin

/**
 * Constructs [PluginCommand] instances without reflection.
 *
 * `PluginCommand`'s constructor is package-private to `org.bukkit.command`. Since this file lives
 * in that package, we get compile-time access to the constructor — no reflective invocation, no
 * `setAccessible(true)`, no version-dependent lookup. If Paper ever changes the constructor
 * signature, this file fails to compile, which is the loudest possible failure mode.
 *
 * This is the cleanest substitute for the previous
 * `PluginCommand.getDeclaredConstructor(String::class.java, Plugin::class.java)` reflection.
 */
object PaperPlanePluginCommandFactory {
  fun create(name: String, owner: Plugin): PluginCommand = PluginCommand(name, owner)
}

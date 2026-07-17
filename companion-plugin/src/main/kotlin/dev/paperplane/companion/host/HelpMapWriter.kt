package dev.paperplane.companion.host

import org.bukkit.command.CommandSender
import org.bukkit.command.PluginCommand
import org.bukkit.help.GenericCommandHelpTopic
import org.bukkit.help.HelpMap
import org.bukkit.help.HelpTopic

/**
 * Writes help topics directly into the `Map<String, HelpTopic>` backing the server's `HelpMap`.
 *
 * Public `HelpMap` is unsuitable for our needs:
 * - `addTopic` uses "existing topics take priority" — re-registering after hot-reload silently
 *   keeps the stale `PluginCommand` reference.
 * - There is no `removeTopic`, so commands removed from `plugin.yml` would leave dangling entries.
 *
 * Direct mutation of the backing map fixes both: `put` replaces, `remove` removes. The backing map
 * is resolved once at companion startup by [ReflectionProbe] via generic-signature lookup (not
 * field name), so this survives a future rename of `SimpleHelpMap.helpTopics`.
 */
class HelpMapWriter(
    private val helpMap: HelpMap,
    private val topics: MutableMap<String, HelpTopic>,
) {

  // Topics we displaced by writing our own into an already-occupied key — e.g. an inner-plugin
  // command whose name collides with a core or other-plugin command. Captured on [register] and put
  // back on [unregister] so a hot-reload cycle never permanently destroys a foreign command's /help
  // entry (which unconditional put/remove would).
  private val displaced = mutableMapOf<String, HelpTopic>()

  /** Add a primary topic for [cmd] and one alias topic per alias. */
  fun register(cmd: PluginCommand) {
    val primaryKey = topicKey(cmd.name)
    put(primaryKey, GenericCommandHelpTopic(cmd))
    for (alias in cmd.aliases) {
      val aliasKey = topicKey(alias)
      if (aliasKey == primaryKey) continue
      put(aliasKey, PaperPlaneAliasHelpTopic(aliasKey, primaryKey, helpMap))
    }
  }

  /** Remove the primary and all alias topics for [cmd], restoring anything they displaced. */
  fun unregister(cmd: PluginCommand) {
    val primaryKey = topicKey(cmd.name)
    restore(primaryKey)
    for (alias in cmd.aliases) {
      val aliasKey = topicKey(alias)
      if (aliasKey == primaryKey) continue
      restore(aliasKey)
    }
  }

  /** Write [topic] at [key], remembering any topic it displaces so [restore] can put it back. */
  private fun put(key: String, topic: HelpTopic) {
    topics[key]?.let { displaced[key] = it }
    topics[key] = topic
  }

  /** Remove our topic at [key], reinstating a displaced foreign topic if there was one. */
  private fun restore(key: String) {
    val prior = displaced.remove(key)
    if (prior != null) topics[key] = prior else topics.remove(key)
  }

  private fun topicKey(name: String): String = if (name.startsWith("/")) name else "/$name"
}

/**
 * Help topic that delegates `getFullText` and `canSee` to a primary command's topic.
 *
 * CraftBukkit's `org.bukkit.craftbukkit.help.CommandAliasHelpTopic` is the natural match here but
 * lives in a protected package — `PluginClassLoader` refuses to load `org.bukkit.*` classes from
 * plugin JARs, and reflecting into it would add a second probe target. Subclassing `HelpTopic`
 * directly (a paper-api class) costs nothing.
 *
 * **UX note**: Paper's `SimpleHelpMap` filters `instanceof CommandAliasHelpTopic` out of the master
 * `/help` index. This subclass won't match that `instanceof`, so PaperPlane-registered aliases
 * appear in the master `/help` listing alongside primaries. For a plugin-dev tool that's arguably
 * useful — every command surface the plugin exposes is visible at a glance.
 */
class PaperPlaneAliasHelpTopic(
    alias: String,
    private val primary: String,
    private val helpMap: HelpMap,
) : HelpTopic() {

  init {
    require(alias != primary) { "alias topic must differ from its primary ($alias)" }
    name = alias
    shortText = "Alias for $primary"
  }

  override fun getFullText(forWho: CommandSender): String {
    val target = helpMap.getHelpTopic(primary) ?: return shortText
    return shortText + "\n" + target.getFullText(forWho)
  }

  override fun canSee(player: CommandSender): Boolean {
    if (amendedPermission != null) return player.hasPermission(amendedPermission)
    val target = helpMap.getHelpTopic(primary) ?: return false
    return target.canSee(player)
  }
}

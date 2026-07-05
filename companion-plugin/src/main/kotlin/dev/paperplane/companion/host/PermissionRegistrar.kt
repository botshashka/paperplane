package dev.paperplane.companion.host

import org.bukkit.Server
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.PluginDescriptionFile

/**
 * Diff-based permission registration via the public `PluginManager.addPermission` /
 * `removePermission` API.
 *
 * Like [CommandRegistrar], maintains a per-host snapshot so reloads can register additions and
 * unregister removals without churn. Children-permission cascades are flattened: every node in the
 * tree gets its own `Permission` object.
 *
 * Zero reflection. The `Permission` class and `PluginManager.addPermission` are public Bukkit API.
 */
class PermissionRegistrar(private val server: Server) {

  /** Permission name → registered Permission. */
  private val applied = mutableMapOf<String, Permission>()

  fun apply(description: PluginDescriptionFile) {
    val desired = description.permissions.associateBy { it.name }

    val toRemove = applied.keys - desired.keys
    for (name in toRemove) {
      applied.remove(name)
      server.pluginManager.removePermission(name)
    }

    for ((name, perm) in desired) {
      if (applied.containsKey(name)) {
        // Re-register if the spec changed (default, description, children).
        // Cheaper to compare-and-update than to skip and risk drift; identity comparison is
        // fast and Permission has no useful equals.
        val current = applied[name]!!
        if (samePermission(current, perm)) continue
        server.pluginManager.removePermission(name)
      }
      server.pluginManager.addPermission(perm)
      applied[name] = perm
    }
  }

  /** Tear down everything we registered. */
  fun clear() {
    for (name in applied.keys.toList()) {
      server.pluginManager.removePermission(name)
    }
    applied.clear()
  }

  fun registered(): Set<String> = applied.keys.toSet()

  /**
   * Identity-ish comparison: same default, same description, same children map. Cheap; matches the
   * subset of [Permission] state that affects runtime semantics.
   */
  private fun samePermission(a: Permission, b: Permission): Boolean {
    if (a.default != b.default) return false
    if (a.description != b.description) return false
    if (a.children != b.children) return false
    return true
  }

  /**
   * Builds a [Permission] from a `permissions:` map entry. Exposed for the host to call before
   * [apply] when permissions need to be constructed from the metadata.json shape rather than
   * `PluginDescriptionFile.getPermissions()`.
   */
  companion object {
    fun build(
        name: String,
        description: String?,
        default: PermissionDefault?,
        children: Map<String, Boolean>,
    ): Permission =
        Permission(name, description, default ?: PermissionDefault.OP, children.toMutableMap())
  }
}

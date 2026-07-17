package dev.paperplane.companion.host

import java.lang.reflect.Method
import org.bukkit.plugin.Plugin

/**
 * Fires Paper's `LifecycleEvents.COMMANDS` re-collection — the same internal sequence
 * `/minecraft:reload` runs after plugins are re-enabled — so a host-loaded plugin's Brigadier
 * commands exist immediately instead of waiting for the next server-wide command rebuild.
 *
 * Paper only *collects* `COMMANDS` lifecycle handlers at its own command-rebuild points (startup,
 * `/minecraft:reload`). A plugin loaded after startup — which is every host-loaded plugin —
 * registers a handler that never fires on its own (spike-verified on Paper 1.21.4, see
 * `brigadier-spike-findings.md` in the repo's local docs). The fix mirrors the tail of Paper's
 * reload path (`MinecraftServer#reloadResources` / `CraftServer#reload`, both verified on
 * ver/1.21.4 source and the 1.21.4 + 1.21.11 shipped jars):
 * ```java
 * PaperCommands.INSTANCE.setValid(); // clear the invalid flag the previous event fire left behind
 * LifecycleEventRunner.INSTANCE.callReloadableRegistrarEvent(
 *     LifecycleEvents.COMMANDS, PaperCommands.INSTANCE, Plugin.class, Cause.RELOAD);
 * ```
 *
 * Re-firing is safe for already-loaded plugins: Paper's registrar overrides a plugin's own main and
 * namespaced labels on re-registration (`PaperCommands#registerIntoDispatcher` with
 * `override=true`), and handlers of disabled plugins are dropped by
 * `LifecycleEventRunner#unregisterAllEventHandlersFor` when the plugin is disabled — so there are
 * no duplicate nodes and no duplicate executions.
 *
 * The re-collection itself does NOT push command-tree packets to clients — Paper's reload calls
 * `CraftServer#syncCommands()` right after the event for that — so callers must follow up with
 * [BrigadierSync.sync].
 *
 * All Paper-internal touchpoints ([runner]/[paperCommands] live in `paper-server`, not the API) are
 * resolved reflectively by [ReflectionProbe.resolveLifecycleCommandSync] and injected here, so this
 * class carries no compile-time dependency on server internals and unit tests can drive it with
 * recording fakes.
 *
 * [fire] rethrows whatever the event handlers throw (wrapped in `InvocationTargetException`) — the
 * host downgrades that to a logged error, because a dev plugin's broken COMMANDS handler must not
 * fail the whole load.
 */
class LifecycleCommandSync(
    private val runner: Any,
    private val callReloadableRegistrarEvent: Method,
    private val paperCommands: Any,
    private val setValid: Method,
    private val commandsEventType: Any,
    private val reloadCause: Any,
) {

  /** Re-runs COMMANDS collection for all loaded plugins. Main-thread only. */
  fun fire() {
    setValid.invoke(paperCommands)
    callReloadableRegistrarEvent.invoke(
        runner,
        commandsEventType,
        paperCommands,
        Plugin::class.java,
        reloadCause,
    )
  }
}

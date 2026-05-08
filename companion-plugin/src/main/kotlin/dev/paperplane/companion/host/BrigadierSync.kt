package dev.paperplane.companion.host

import org.bukkit.Server

/**
 * Notifies Paper that the command tree changed so connected clients see the update.
 *
 * `syncCommands()` is on `CraftServer` (Paper-internal) but not on the Bukkit `Server` interface
 * — there's no public-API substitute. Calling it via reflection is unavoidable.
 *
 * It's best-effort: if the method has been renamed or the call fails (e.g. during a partially
 * torn-down server), tab-complete may show stale state but the registered commands still execute
 * correctly. We don't fail the host on a missed sync.
 */
internal object BrigadierSync {
  fun sync(server: Server) {
    try {
      server.javaClass.getMethod("syncCommands").invoke(server)
    } catch (
        @Suppress("TooGenericExceptionCaught") // Best-effort — never fail the host on this.
        _: Exception) {
      // Stale Brigadier state is acceptable; broken host is not.
    }
  }
}

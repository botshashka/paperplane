package dev.paperplane.companion.host

import org.bukkit.Server

/**
 * Notifies Paper that the command tree changed so connected clients see the update.
 *
 * Calls `CraftServer#syncCommands()` reflectively (it's not on the Bukkit `Server` interface).
 * That call rebuilds the server-side Brigadier dispatcher AND pushes `ClientboundCommandsPacket`
 * to every online player, so we don't need a separate per-player `updateCommands()` loop — adding
 * one races with Paper's own send on the shared dispatcher tree and produces a `CME`.
 *
 * Best-effort: failures (e.g. partially torn-down server) are swallowed so a missed refresh never
 * takes down the host. The worst case is stale tab-complete until the next sync.
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

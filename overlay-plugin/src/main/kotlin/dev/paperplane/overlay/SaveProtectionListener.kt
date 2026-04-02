package dev.paperplane.overlay

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent

class SaveProtectionListener(private val statusBar: BuildStatusBar) : Listener {
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        if (statusBar.isSaving) event.isCancelled = true
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (statusBar.isSaving) event.isCancelled = true
    }
}

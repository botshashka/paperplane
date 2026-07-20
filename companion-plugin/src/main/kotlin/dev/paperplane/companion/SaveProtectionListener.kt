package dev.paperplane.companion

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent

class SaveProtectionListener(private val handler: CompanionMessageHandler) : Listener {
  @EventHandler
  fun onBlockBreak(event: BlockBreakEvent) {
    if (handler.blockWorldEdits) event.isCancelled = true
  }

  @EventHandler
  fun onBlockPlace(event: BlockPlaceEvent) {
    if (handler.blockWorldEdits) event.isCancelled = true
  }
}

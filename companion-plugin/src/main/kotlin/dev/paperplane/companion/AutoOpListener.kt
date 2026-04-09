package dev.paperplane.companion

import com.google.gson.Gson
import java.io.File
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * Auto-ops every joining player on the dev server so you don't have to `/op` yourself after every
 * restart. Names listed in `.paperplane/op-banlist.json` are skipped — that's how PaperPlane
 * supports a persistent deop: the CLI writes the file from `paperplane.yml`'s `server.op-banlist`
 * entry and this listener honours it on every join.
 */
class AutoOpListener(private val serverDir: File) : Listener {
  private val banlistFile = File(serverDir, ".paperplane/op-banlist.json")
  private val gson = Gson()

  @EventHandler
  fun onPlayerJoin(event: PlayerJoinEvent) {
    if (event.player.isOp) return
    if (event.player.name in readBanlist()) return
    event.player.isOp = true
  }

  private fun readBanlist(): Set<String> {
    if (!banlistFile.exists()) return emptySet()
    return try {
      @Suppress("UNCHECKED_CAST")
      val list = gson.fromJson(banlistFile.readText(), List::class.java) as? List<String>
      list?.toSet() ?: emptySet()
    } catch (_: Exception) {
      emptySet()
    }
  }
}

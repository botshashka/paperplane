package dev.paperplane.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.util.concurrent.TimeUnit
import org.slf4j.Logger

@Plugin(
    id = "paperplane-reconnect",
    name = "PaperPlane Reconnect",
    version = "0.1.0",
    description = "Holds player connections during PaperPlane rebuilds"
)
class ReconnectPlugin @Inject constructor(
    private val server: ProxyServer,
    private val logger: Logger
) {

    @Subscribe
    fun onProxyInit(event: ProxyInitializeEvent) {
        logger.info("PaperPlane reconnect plugin enabled")
    }

    @Subscribe
    fun onKickedFromServer(event: KickedFromServerEvent) {
        val player = event.player
        val serverName = event.server.serverInfo.name

        // Keep the player on the proxy instead of disconnecting them
        event.result = KickedFromServerEvent.Notify.create(
            Component.text("Rebuilding... reconnecting shortly", NamedTextColor.YELLOW)
        )

        // Schedule reconnect attempts
        scheduleReconnect(player, serverName, 1)
    }

    private fun scheduleReconnect(
        player: com.velocitypowered.api.proxy.Player,
        serverName: String,
        attempt: Int
    ) {
        if (attempt > 30) {
            player.disconnect(Component.text("Server did not come back after 60s", NamedTextColor.RED))
            return
        }

        server.scheduler.buildTask(this, Runnable {
            if (!player.isActive) return@Runnable

            val target = server.getServer(serverName).orElse(null) ?: return@Runnable
            val result = player.createConnectionRequest(target).connect().join()

            if (!result.isSuccessful) {
                scheduleReconnect(player, serverName, attempt + 1)
            }
        }).delay(2, TimeUnit.SECONDS).schedule()
    }
}

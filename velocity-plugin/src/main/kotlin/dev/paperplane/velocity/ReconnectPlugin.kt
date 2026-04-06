package dev.paperplane.velocity

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.slf4j.Logger

@Plugin(
    id = "paperplane-transfer",
    name = "PaperPlane Transfer",
    description = "Handles seamless player transfers during PaperPlane blue/green rebuilds",
)
class ReconnectPlugin
@Inject
constructor(private val server: ProxyServer, private val logger: Logger) {
  companion object {
    private const val STATUS_POLL_INTERVAL_MS = 100L
    private const val TRANSFER_TIMEOUT_SECONDS = 5L
  }

  private val gson = Gson()
  @Volatile private var activeServer: String = "server"
  private val statusFile = File("active-server.json")

  @Subscribe
  fun onProxyInit(_event: ProxyInitializeEvent) {
    // Read initial state
    pollStatus()

    // Poll active-server.json every 100ms
    server.scheduler
        .buildTask(this, Runnable { pollStatus() })
        .repeat(STATUS_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .schedule()

    logger.info("PaperPlane transfer plugin enabled")
  }

  @Subscribe
  fun onChooseServer(event: PlayerChooseInitialServerEvent) {
    server.getServer(activeServer).ifPresent { event.setInitialServer(it) }
  }

  @Subscribe
  fun onKicked(event: KickedFromServerEvent) {
    // Safety net: redirect to active server instead of disconnecting
    val target = server.getServer(activeServer).orElse(null) ?: return
    // Only redirect if the active server is different from the one that kicked us
    if (event.server.serverInfo.name != activeServer) {
      event.result = KickedFromServerEvent.RedirectPlayer.create(target)
    } else {
      event.result =
          KickedFromServerEvent.Notify.create(
              Component.text("Server restarting...", NamedTextColor.YELLOW)
          )
    }
  }

  private fun pollStatus() {
    if (!statusFile.exists()) return
    try {
      val json = gson.fromJson(statusFile.readText(), JsonObject::class.java) ?: return
      val newActive = json.get("active")?.asString ?: return
      val transfer = json.get("transfer")?.asBoolean ?: false

      if (newActive != activeServer) {
        if (server.getServer(newActive).isEmpty) {
          logger.warn("Active server '{}' not registered on proxy", newActive)
        }
        activeServer = newActive
      }

      if (transfer) {
        // Clear transfer flag BEFORE transferring — pollStatus runs on a thread pool,
        // so a concurrent poll must not see transfer=true again while we're blocking
        val updated =
            JsonObject().apply {
              addProperty("active", newActive)
              addProperty("transfer", false)
            }
        statusFile.writeText(gson.toJson(updated))
        val success = transferPlayers(newActive)
        if (success) {
          File(statusFile.parentFile, "transfer-complete")
              .writeText(System.currentTimeMillis().toString())
        } else {
          logger.warn("Transfer to {} had failures", newActive)
        }
      }
    } catch (e: IOException) {
      logger.warn("Failed to poll active-server.json: {}", e.message)
    } catch (e: JsonParseException) {
      logger.warn("Failed to poll active-server.json: {}", e.message)
    }
  }

  private fun transferPlayers(targetName: String): Boolean {
    val target = server.getServer(targetName).orElse(null)
    if (target == null) {
      logger.warn("Transfer target server '{}' not found", targetName)
      return false
    }

    val futures = mutableListOf<CompletableFuture<*>>()
    for (player in server.allPlayers) {
      val currentServer = player.currentServer.orElse(null)?.serverInfo?.name
      if (currentServer != targetName) {
        logger.info("Transferring {} to {}", player.username, targetName)
        val future =
            player.createConnectionRequest(target).connect().thenAccept { result ->
              if (!result.isSuccessful) {
                logger.warn(
                    "Transfer failed for {}: {}",
                    player.username,
                    result.reasonComponent.orElse(Component.text("unknown")),
                )
              }
            }
        futures.add(future)
      }
    }

    if (futures.isEmpty()) return true

    return try {
      CompletableFuture.allOf(*futures.toTypedArray())
          .get(TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      true
    } catch (e: TimeoutException) {
      logger.warn("Some transfers did not complete in time: {}", e.message)
      false
    } catch (e: ExecutionException) {
      logger.warn("Some transfers did not complete in time: {}", e.message)
      false
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      logger.warn("Some transfers did not complete in time: {}", e.message)
      false
    }
  }
}

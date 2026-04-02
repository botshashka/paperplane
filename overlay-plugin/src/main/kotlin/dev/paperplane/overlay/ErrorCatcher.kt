package dev.paperplane.overlay

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.java.JavaPlugin

class ErrorCatcher(private val plugin: JavaPlugin) : Listener {
    private val overlayPackage = "dev.paperplane.overlay"

    @EventHandler
    fun onPluginEnable(event: PluginEnableEvent) {
        // Skip our own plugin
        if (event.plugin.name == plugin.name) return
    }

    fun broadcastError(throwable: Throwable) {
        val frames = throwable.stackTrace
            .filter { !it.className.startsWith(overlayPackage) }
            .take(5)

        val header = Component.text("  Exception: ", NamedTextColor.RED, TextDecoration.BOLD)
            .append(Component.text(throwable.javaClass.simpleName, NamedTextColor.WHITE))
        val message = Component.text("  ${throwable.message ?: "no message"}", NamedTextColor.RED)

        val components = mutableListOf(
            Component.empty(),
            header,
            message
        )

        for (frame in frames) {
            val location = "${frame.fileName}:${frame.lineNumber}"
            val method = "${frame.className.substringAfterLast('.')}.${frame.methodName}"
            components.add(
                Component.text("    at ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(method, NamedTextColor.GRAY))
                    .append(Component.text(" ($location)", NamedTextColor.WHITE))
            )
        }

        if (throwable.stackTrace.size > 5) {
            components.add(
                Component.text("    ... ${throwable.stackTrace.size - 5} more", NamedTextColor.DARK_GRAY)
            )
        }

        components.add(Component.empty())

        // Send to all ops
        for (player in plugin.server.onlinePlayers) {
            if (player.isOp) {
                for (comp in components) {
                    player.sendMessage(comp)
                }
            }
        }
    }
}

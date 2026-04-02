package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.ui.TerminalUI
import java.io.File

class CleanCommand : CliktCommand(name = "clean") {
    private val force by option("--force", "-f", help = "Skip confirmation").flag()
    private val projectDir = File(System.getProperty("user.dir"))

    override fun run() {
        val ppDir = File(projectDir, ".paperplane")
        if (!ppDir.exists()) {
            TerminalUI.error("No .paperplane/ directory found — nothing to clean")
            return
        }

        val serverDir = File(ppDir, "server")
        val cacheDir = File(ppDir, "cache")

        val serverSize = dirSize(serverDir)
        val cacheSize = dirSize(cacheDir)
        val totalSize = serverSize + cacheSize

        TerminalUI.blank()
        TerminalUI.status("This will delete:")
        if (serverDir.exists()) {
            TerminalUI.info("Server:", "${serverDir.absolutePath} (${formatSize(serverSize)})")
            TerminalUI.status("  World data, server config, plugins/")
        }
        if (cacheDir.exists()) {
            TerminalUI.info("Cache:", "${cacheDir.absolutePath} (${formatSize(cacheSize)})")
            TerminalUI.status("  Downloaded Paper jars")
        }
        TerminalUI.blank()
        TerminalUI.status("Total: ${formatSize(totalSize)}")
        TerminalUI.blank()

        if (!force) {
            print("  Are you sure? (y/N): ")
            val answer = readlnOrNull()?.trim()?.lowercase()
            if (answer != "y" && answer != "yes") {
                TerminalUI.status("Cancelled")
                return
            }
        }

        if (serverDir.exists()) {
            serverDir.deleteRecursively()
            TerminalUI.success("Deleted server/")
        }
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
            TerminalUI.success("Deleted cache/")
        }

        TerminalUI.blank()
        TerminalUI.success("Clean complete — next 'ppl dev' will set up a fresh server")
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

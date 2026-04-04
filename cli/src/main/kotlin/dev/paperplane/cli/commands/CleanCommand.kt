package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.ui.TerminalUI
import java.io.File

class CleanCommand : CliktCommand(name = "clean") {
  private val force by option("--force", "-f", help = "Skip confirmation").flag()
  private val all by
      option("--all", "-a", help = "Also delete cache (downloaded Paper/Velocity/JBR)").flag()
  private val projectDir = File(System.getProperty("user.dir"))

  override fun run() {
    val ppDir = File(projectDir, ".paperplane")
    if (!ppDir.exists()) {
      TerminalUI.blank()
      TerminalUI.error("No .paperplane/ directory found — nothing to clean")
      return
    }

    val serverDir = File(ppDir, "server")
    val swapDir = File(ppDir, "server-swap")
    val proxyDir = File(ppDir, "proxy")
    val cacheDir = File(ppDir, "cache")

    val dirsToDelete = mutableListOf<File>()
    if (serverDir.exists()) dirsToDelete.add(serverDir)
    if (swapDir.exists()) dirsToDelete.add(swapDir)
    if (proxyDir.exists()) dirsToDelete.add(proxyDir)
    if (all && cacheDir.exists()) dirsToDelete.add(cacheDir)

    if (dirsToDelete.isEmpty()) {
      TerminalUI.blank()
      TerminalUI.status("Nothing to clean")
      return
    }

    val totalSize = dirsToDelete.sumOf { dirSize(it) }

    TerminalUI.beginBlock()
    TerminalUI.status("This will delete:")
    for (dir in dirsToDelete) {
      TerminalUI.info("${dir.name}/", formatSize(dirSize(dir)))
    }
    if (!all && cacheDir.exists()) {
      TerminalUI.blank()
      TerminalUI.status("Cache preserved (${formatSize(dirSize(cacheDir))}). Use --all to delete.")
    }
    TerminalUI.blank()
    TerminalUI.status("Total: ${formatSize(totalSize)}")
    TerminalUI.endBlock()

    if (!force) {
      println()
      print("  Are you sure? (y/N): ")
      val answer = readlnOrNull()?.trim()?.lowercase()
      if (answer != "y" && answer != "yes") {
        TerminalUI.status("Cancelled")
        return
      }
    }

    TerminalUI.beginBlock()
    for (dir in dirsToDelete) {
      dir.deleteRecursively()
      TerminalUI.success("Deleted ${dir.name}/")
    }
    TerminalUI.blank()
    TerminalUI.success("Clean complete — next 'ppl dev' will set up a fresh server")
    TerminalUI.endBlock()
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

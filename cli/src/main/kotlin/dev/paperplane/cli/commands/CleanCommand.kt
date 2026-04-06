package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.util.Platform
import java.io.File

class CleanCommand : CliktCommand(name = "clean") {

  private val force by option("--force", "-f", help = "Skip confirmation").flag()
  private val all by
      option("--all", "-a", help = "Also delete cache (downloaded Paper/Velocity/JBR)").flag()
  private val projectDir = File(System.getProperty("user.dir"))

  override fun run() {
    try {
      runInternal()
    } finally {
      TerminalUI.endView()
    }
  }

  private fun runInternal() {
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

    val totalSize = dirsToDelete.sumOf { Platform.dirSize(it) }

    TerminalUI.beginBlock()
    TerminalUI.status("This will delete:")
    for (dir in dirsToDelete) {
      TerminalUI.info("${dir.name}/", Platform.formatSize(Platform.dirSize(dir)))
    }
    if (!all && cacheDir.exists()) {
      TerminalUI.blank()
      TerminalUI.status(
          "Cache preserved (${Platform.formatSize(Platform.dirSize(cacheDir))}). Use --all to delete."
      )
    }
    TerminalUI.blank()
    TerminalUI.status("Total: ${Platform.formatSize(totalSize)}")
    TerminalUI.endBlock()

    if (!force && !TerminalUI.confirm("Are you sure?")) {
      TerminalUI.status("Cancelled")
      return
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
}

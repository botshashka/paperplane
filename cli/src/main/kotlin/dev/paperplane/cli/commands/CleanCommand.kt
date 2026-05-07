package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.ui.InteractivePrompts
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.util.Platform
import java.io.File

class CleanCommand(
    private val ui: TerminalUI,
    private val prompts: InteractivePrompts,
) : CliktCommand(name = "clean") {

  private val force by option("--force", "-f", help = "Skip confirmation").flag()
  private val all by
      option("--all", "-a", help = "Also delete cache (Paper/Velocity/JBR + plugin downloads)")
          .flag()
  private val projectDir = File(System.getProperty("user.dir"))

  override fun run() {
    try {
      runInternal()
    } finally {
      ui.endView()
    }
  }

  private fun runInternal() {
    val ppDir = File(projectDir, ".paperplane")
    if (!ppDir.exists()) {
      ui.blank()
      ui.error("No .paperplane/ directory found — nothing to clean")
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
      ui.blank()
      ui.status("Nothing to clean")
      return
    }

    val totalSize = dirsToDelete.sumOf { Platform.dirSize(it) }

    ui.block {
      status("This will delete:")
      for (dir in dirsToDelete) {
        info("${dir.name}/", Platform.formatSize(Platform.dirSize(dir)))
      }
      if (!all && cacheDir.exists()) {
        blank()
        status(
            "Cache preserved (${Platform.formatSize(Platform.dirSize(cacheDir))}). Use --all to delete."
        )
      }
      blank()
      status("Total: ${Platform.formatSize(totalSize)}")
    }

    if (!force && !prompts.confirm("Are you sure?")) {
      ui.status("Cancelled")
      return
    }

    ui.block {
      for (dir in dirsToDelete) {
        dir.deleteRecursively()
        success("Deleted ${dir.name}/")
      }
      blank()
      success("Clean complete — next 'ppl dev' will set up a fresh server")
    }
  }
}

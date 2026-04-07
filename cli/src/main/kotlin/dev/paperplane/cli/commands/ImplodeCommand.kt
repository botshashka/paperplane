package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.ui.InteractivePrompts
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.util.Platform
import java.io.File

class ImplodeCommand : CliktCommand(name = "implode") {
  companion object {
    private const val PAPERPLANE_MARKER = "# paperplane"
  }

  private val force by option("--force", "-f", help = "Skip confirmation").flag()

  override fun run() {
    val installDir = Platform.paperplaneHome

    if (!installDir.exists()) {
      TerminalUI.blank()
      TerminalUI.error("No ~/.paperplane/ directory found — nothing to remove")
      return
    }

    val totalSize = Platform.dirSize(installDir)

    val rcFiles = shellRcFiles()
    val affectedRcFiles = rcFiles.filter { it.readText().contains(PAPERPLANE_MARKER) }

    TerminalUI.block {
      status("This will remove:")
      info("~/.paperplane/", Platform.formatSize(totalSize))
      if (affectedRcFiles.isNotEmpty()) {
        info("PATH entries", affectedRcFiles.joinToString(", ") { "~/${it.name}" })
      }
      blank()
      status("Total: ${Platform.formatSize(totalSize)}")
    }

    if (!force && !InteractivePrompts.confirm("Are you sure? This will remove ppl from your system.")) {
      TerminalUI.status("Cancelled")
      return
    }

    TerminalUI.block {
      // Remove PATH entries
      if (Platform.isWindows) {
        removeWindowsPathEntry()
      }
      for (rcFile in affectedRcFiles) {
        removePaperplaneBlock(rcFile)
        success("Cleaned ${rcFile.name}")
      }

      // Remove fish completions if present
      val fishCompletion = File(Platform.userHome, ".config/fish/completions/ppl.fish")
      if (fishCompletion.exists()) {
        fishCompletion.delete()
        success("Removed fish completion")
      }

      // Delete the installation directory
      installDir.deleteRecursively()
      success("Deleted ~/.paperplane/")

      blank()
      status("paperplane has been removed. Goodbye!")
    }
  }

  private fun shellRcFiles(): List<File> {
    if (Platform.isWindows) return emptyList()
    return listOf(".bashrc", ".zshrc", ".profile")
        .map { File(Platform.userHome, it) }
        .filter { it.exists() }
  }

  internal fun removePaperplaneBlock(rcFile: File) {
    val lines = rcFile.readLines()
    val filtered = mutableListOf<String>()
    var inBlock = false
    for (line in lines) {
      if (line.trim() == PAPERPLANE_MARKER) {
        inBlock = true
        continue
      }
      if (inBlock) {
        // Skip all lines that belong to the paperplane block
        if (line.contains(".paperplane") || line.contains("fpath") || line.isBlank()) {
          continue
        }
        // Non-matching line ends the block
        inBlock = false
      }
      filtered.add(line)
    }
    rcFile.writeText(filtered.joinToString("\n") + "\n")
  }

  private fun removeWindowsPathEntry() {
    try {
      val binDir = File(Platform.paperplaneHome, "bin").absolutePath
      // Read current user PATH from registry
      val proc =
          ProcessBuilder(
                  "cmd",
                  "/c",
                  "reg",
                  "query",
                  "HKCU\\Environment",
                  "/v",
                  "Path",
              )
              .redirectErrorStream(true)
              .start()
      val output = proc.inputStream.bufferedReader().use { it.readText() }
      proc.waitFor()

      // Extract the PATH value and remove our entry
      val pathLine = output.lines().firstOrNull { it.contains("REG_") } ?: return
      val currentPath =
          pathLine.substringAfter("REG_EXPAND_SZ").trim().ifEmpty {
            pathLine.substringAfter("REG_SZ").trim()
          }
      val newPath =
          currentPath
              .split(";")
              .filter { !it.contains(".paperplane", ignoreCase = true) }
              .joinToString(";")

      if (newPath != currentPath) {
        ProcessBuilder(
                "cmd",
                "/c",
                "reg",
                "add",
                "HKCU\\Environment",
                "/v",
                "Path",
                "/t",
                "REG_EXPAND_SZ",
                "/d",
                newPath,
                "/f",
            )
            .redirectErrorStream(true)
            .start()
            .waitFor()
        TerminalUI.success("Removed PATH entry from registry")
      }
    } catch (_: Exception) {
      // Best-effort; user can clean up manually
    }
  }
}

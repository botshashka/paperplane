package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.Versions
import dev.paperplane.cli.config.DevMode
import dev.paperplane.cli.config.PaperPlaneConfig
import dev.paperplane.cli.devserver.BlueGreenMode
import dev.paperplane.cli.devserver.DevSession
import dev.paperplane.cli.devserver.HotReloadMode
import dev.paperplane.cli.devserver.RestartMode
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.server.PaperDownloader
import dev.paperplane.cli.ui.EXIT_CANCELLED
import dev.paperplane.cli.ui.InteractivePrompts
import dev.paperplane.cli.ui.PromptCancelledException
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DevCommand(
    private val ui: TerminalUI,
    private val prompts: InteractivePrompts,
) : CliktCommand(name = "dev") {
  private val modeFlag by option("--mode", "-m", help = "Dev mode: hot-reload, blue-green, restart")
  private val projectDir = File(System.getProperty("user.dir"))

  override fun run() {
    try {
      runInternal()
    } catch (_: PromptCancelledException) {
      ui.cancelled()
      throw ProgramResult(EXIT_CANCELLED)
    } finally {
      prompts.endInteractiveView()
      ui.endView()
    }
  }

  private fun runInternal() {
    val version = Versions.paperplaneVersion()
    ui.header(version)

    val config = PaperPlaneConfig.load(projectDir, ui)
    val ppDir = File(projectDir, ".paperplane")

    migrateOldLayout(ppDir)

    if (!ensureEulaAccepted(File(ppDir, "server"))) return

    val session =
        DevSession(
            config = config,
            ppDir = ppDir,
            gradle = GradleBridge(projectDir, ui),
            downloader = PaperDownloader(File(ppDir, "cache")),
            projectDir = projectDir,
            ui = ui,
        )

    val resolvedMode =
        modeFlag?.let {
          try {
            DevMode.valueOf(it.uppercase().replace("-", "_"))
          } catch (_: Exception) {
            ui.block { error("Unknown mode: $it (expected: hot-reload, blue-green, restart)") }
            return
          }
        } ?: config.dev.mode

    when (resolvedMode) {
      DevMode.HOT_RELOAD -> HotReloadMode(session).run()
      DevMode.BLUE_GREEN -> BlueGreenMode(session).run()
      DevMode.RESTART -> RestartMode(session).run()
    }
  }

  /**
   * Ensures the Minecraft EULA has been accepted for this server dir. Returns true if accepted (or
   * already accepted), false if the user declined. Persists acceptance as `eula.txt`.
   */
  private fun ensureEulaAccepted(serverDir: File): Boolean {
    val eulaFile = File(serverDir, "eula.txt")
    if (eulaFile.exists() && eulaFile.readText().contains("eula=true")) return true

    val choice =
        prompts.select(
            "Do you accept the Minecraft EULA?",
            listOf("Yes", "No"),
            note = "https://aka.ms/MinecraftEULA",
        )
    if (choice != 0) {
      ui.block { error("You must accept the Minecraft EULA to run a server") }
      return false
    }

    serverDir.mkdirs()
    eulaFile.writeText("eula=true\n")
    return true
  }

  private fun migrateOldLayout(ppDir: File) {
    val oldBlue = File(ppDir, "server-blue")
    val newServer = File(ppDir, "server")
    val oldGreen = File(ppDir, "server-green")

    val needsMigration = (oldBlue.exists() && !newServer.exists()) || oldGreen.exists()
    if (!needsMigration) return

    ui.block {
      if (oldBlue.exists() && !newServer.exists()) {
        Files.move(oldBlue.toPath(), newServer.toPath(), StandardCopyOption.REPLACE_EXISTING)
        info("Migrated:", "server-blue/ → server/")
      }
      if (oldGreen.exists()) {
        oldGreen.deleteRecursively()
        info("Cleaned up:", "server-green/ (no longer needed)")
      }
    }
  }
}

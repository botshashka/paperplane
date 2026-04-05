package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
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
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DevCommand : CliktCommand(name = "dev") {
  private val modeFlag by option("--mode", "-m", help = "Dev mode: hot-reload, blue-green, restart")
  private val projectDir = File(System.getProperty("user.dir"))

  override fun run() {
    val version = Versions.paperplaneVersion()
    TerminalUI.header(version)

    val config = PaperPlaneConfig.load(projectDir)
    val ppDir = File(projectDir, ".paperplane")
    ppDir.mkdirs()

    migrateOldLayout(ppDir)

    val session =
        DevSession(
            config = config,
            ppDir = ppDir,
            gradle = GradleBridge(projectDir),
            downloader = PaperDownloader(File(ppDir, "cache")),
            projectDir = projectDir,
        )

    val resolvedMode =
        modeFlag?.let {
          try {
            DevMode.valueOf(it.uppercase().replace("-", "_"))
          } catch (_: Exception) {
            TerminalUI.beginBlock()
            TerminalUI.error("Unknown mode: $it (expected: hot-reload, blue-green, restart)")
            TerminalUI.endBlock()
            return
          }
        } ?: config.dev.mode

    when (resolvedMode) {
      DevMode.HOT_RELOAD -> HotReloadMode(session).run()
      DevMode.BLUE_GREEN -> BlueGreenMode(session).run()
      DevMode.RESTART -> RestartMode(session).run()
    }
  }

  private fun migrateOldLayout(ppDir: File) {
    val oldBlue = File(ppDir, "server-blue")
    val newServer = File(ppDir, "server")
    val oldGreen = File(ppDir, "server-green")

    val needsMigration = (oldBlue.exists() && !newServer.exists()) || oldGreen.exists()
    if (!needsMigration) return

    TerminalUI.beginBlock()
    if (oldBlue.exists() && !newServer.exists()) {
      Files.move(oldBlue.toPath(), newServer.toPath(), StandardCopyOption.REPLACE_EXISTING)
      TerminalUI.info("Migrated:", "server-blue/ → server/")
    }
    if (oldGreen.exists()) {
      oldGreen.deleteRecursively()
      TerminalUI.info("Cleaned up:", "server-green/ (no longer needed)")
    }
    TerminalUI.endBlock()
  }
}

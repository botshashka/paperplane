package dev.paperplane.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import dev.paperplane.cli.commands.CleanCommand
import dev.paperplane.cli.commands.DevCommand
import dev.paperplane.cli.commands.InitCommand
import dev.paperplane.cli.commands.SetupCommand
import dev.paperplane.cli.commands.TestCommand
import dev.paperplane.cli.ui.TerminalUI

class PaperPlane : CliktCommand(name = "ppl") {
  init {
    context {
      helpFormatter = { ctx ->
        object : MordantHelpFormatter(ctx) {
          override fun styleSectionTitle(title: String) = (TextColors.cyan + TextStyles.bold)(title)

          override fun styleUsageTitle(title: String) = (TextColors.cyan + TextStyles.bold)(title)

          override fun styleOptionName(name: String) = TextColors.brightWhite(name)

          override fun styleSubcommandName(name: String) = TextColors.brightWhite(name)

          override fun styleMetavar(metavar: String) = TextColors.cyan(metavar)
        }
      }
    }
  }

  override fun run() = Unit
}

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    val version = PaperPlane::class.java.`package`?.implementationVersion ?: "0.1.0"
    TerminalUI.header(version)
    TerminalUI.info("dev", "Start dev server with file watching")
    TerminalUI.info("init", "Scaffold a new Paper plugin project")
    TerminalUI.info("setup", "Download and configure Paper server")
    TerminalUI.info("test", "Run tests via Gradle")
    TerminalUI.info("clean", "Clean .paperplane directory")
    TerminalUI.blank()
    TerminalUI.status("Run 'ppl <command> --help' for more info")
    return
  }

  PaperPlane()
      .subcommands(InitCommand(), DevCommand(), SetupCommand(), TestCommand(), CleanCommand())
      .main(args)
}

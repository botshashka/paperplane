package dev.paperplane.cli

import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import dev.paperplane.cli.commands.CleanCommand
import dev.paperplane.cli.commands.CreateCommand
import dev.paperplane.cli.commands.DevCommand
import dev.paperplane.cli.commands.FormatCommand
import dev.paperplane.cli.commands.ImplodeCommand
import dev.paperplane.cli.commands.InitCommand
import dev.paperplane.cli.commands.TestCommand
import dev.paperplane.cli.commands.UpgradeCommand
import dev.paperplane.cli.ui.TerminalUI

class PaperPlane : CliktCommand(name = "ppl") {
  override fun aliases() =
      mapOf("new" to listOf("create"), "setup" to listOf("init"), "fmt" to listOf("format"))

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
  // Safety net: restore terminal attributes if the JVM exits while still in raw mode
  // (hard SIGTERM, unexpected System.exit, crash during scaffolding).
  Runtime.getRuntime()
      .addShutdownHook(Thread({ TerminalUI.restoreTerminalIfNeeded() }, "terminal-restore"))


  if (args.isEmpty()) {
    val version = Versions.paperplaneVersion()
    TerminalUI.header(version)
    TerminalUI.info("dev", "Start dev server with file watching")
    TerminalUI.info("create", "Scaffold a new Paper plugin project")
    TerminalUI.info("init", "Add PaperPlane to an existing project")
    TerminalUI.info("test", "Run tests via Gradle")
    TerminalUI.info("format", "Format source code with Spotless")
    TerminalUI.info("clean", "Clean .paperplane directory")
    TerminalUI.info("upgrade", "Update ppl to the latest version")
    TerminalUI.info("implode", "Uninstall ppl completely")
    TerminalUI.blank()
    TerminalUI.status("Run 'ppl <command> --help' for more info")
    return
  }

  PaperPlane()
      .completionOption()
      .subcommands(
          CreateCommand(),
          InitCommand(),
          DevCommand(),
          TestCommand(),
          FormatCommand(),
          CleanCommand(),
          UpgradeCommand(),
          ImplodeCommand(),
      )
      .main(args)
}

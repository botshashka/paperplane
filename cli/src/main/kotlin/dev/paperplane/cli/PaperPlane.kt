package dev.paperplane.cli

import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import dev.paperplane.cli.commands.BuildCommand
import dev.paperplane.cli.commands.CleanCommand
import dev.paperplane.cli.commands.CreateCommand
import dev.paperplane.cli.commands.DevCommand
import dev.paperplane.cli.commands.ImplodeCommand
import dev.paperplane.cli.commands.InitCommand
import dev.paperplane.cli.commands.PluginCommand
import dev.paperplane.cli.commands.UpgradeCommand
import dev.paperplane.cli.ui.AnsiTerminal
import dev.paperplane.cli.ui.InteractivePrompts
import dev.paperplane.cli.ui.TerminalUI

class PaperPlane : CliktCommand(name = "ppl") {
  override fun aliases() =
      mapOf(
          "new" to listOf("create"),
          "setup" to listOf("init"),
          "p" to listOf("plugin"),
      )

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
  // Single Terminal seam for the whole CLI process. All UI, prompts, dev-server modes, and
  // helpers wire their I/O through these two instances — threaded via constructor injection.
  val terminal = AnsiTerminal()
  val ui = TerminalUI(terminal)
  val prompts = InteractivePrompts(terminal)

  // Safety net: restore terminal attributes if the JVM exits while still in raw mode
  // (hard SIGTERM, unexpected System.exit, crash during scaffolding).
  Runtime.getRuntime()
      .addShutdownHook(Thread({ prompts.restoreTerminalIfNeeded() }, "terminal-restore"))

  if (args.isEmpty()) {
    val version = Versions.paperplaneVersion()
    ui.header(version)
    ui.block {
      info("dev", "Start dev server with file watching")
      info("create", "Scaffold a new Paper plugin project")
      info("init", "Add PaperPlane to an existing project")
      info("build", "Build the deployable plugin jar")
      info("clean", "Clean .paperplane directory")
      info("plugin", "Manage dev-server plugin dependencies")
      info("upgrade", "Update ppl to the latest version")
      info("implode", "Uninstall ppl completely")
      blank()
      status("Run 'ppl <command> --help' for more info")
    }
    ui.endView()
    return
  }

  PaperPlane()
      .completionOption()
      .subcommands(
          CreateCommand(ui, prompts),
          InitCommand(ui),
          DevCommand(ui, prompts),
          BuildCommand(ui),
          CleanCommand(ui, prompts),
          PluginCommand(ui),
          UpgradeCommand(ui),
          ImplodeCommand(ui, prompts),
      )
      .main(args)
}

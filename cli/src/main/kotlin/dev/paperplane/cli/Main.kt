package dev.paperplane.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import dev.paperplane.cli.commands.CleanCommand
import dev.paperplane.cli.commands.DevCommand
import dev.paperplane.cli.commands.InitCommand
import dev.paperplane.cli.commands.SetupCommand
import dev.paperplane.cli.commands.TestCommand

class PaperPlane : CliktCommand(name = "ppl") {
    override fun run() = Unit
}

fun main(args: Array<String>) {
    PaperPlane()
        .subcommands(InitCommand(), DevCommand(), SetupCommand(), TestCommand(), CleanCommand())
        .main(args)
}

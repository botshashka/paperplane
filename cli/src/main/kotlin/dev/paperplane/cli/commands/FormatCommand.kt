package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.Versions
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.ui.TerminalUI
import java.io.File

class FormatCommand : CliktCommand(name = "format") {
  private val check by
      option("--check", "-c", help = "Check formatting without modifying files").flag()
  private val projectDir = File(System.getProperty("user.dir"))

  override fun run() {
    try {
      runInternal()
    } finally {
      TerminalUI.endView()
    }
  }

  private fun runInternal() {
    TerminalUI.header(Versions.paperplaneVersion())

    val gradle = GradleBridge(projectDir)
    try {
      val spinMessage = if (check) "Checking formatting..." else "Formatting..."
      val result = TerminalUI.spin(spinMessage) { gradle.format(check = check) }

      TerminalUI.beginBlock()
      if (result.success) {
        TerminalUI.success(if (check) "Formatting OK" else "Formatted")
      } else if (result.taskMissing) {
        TerminalUI.error("No formatter configured for this project")
        TerminalUI.status(
            "ppl format runs Spotless — add the Spotless plugin to build.gradle.kts to enable it")
        TerminalUI.status("See https://github.com/diffplug/spotless for setup")
      } else {
        val summary = result.rootMessage ?: "Format failed"
        TerminalUI.error(summary)
        if (result.outputLines.isNotEmpty()) {
          TerminalUI.buildError("Spotless output", null, result.outputLines.joinToString("\n"))
        }
      }
      TerminalUI.endBlock()
    } finally {
      gradle.close()
    }
  }
}

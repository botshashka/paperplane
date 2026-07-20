package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.Versions
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.ui.TerminalUI
import java.io.File

open class FormatCommand(private val ui: TerminalUI) : CliktCommand(name = "format") {
  private val check by
      option("--check", "-c", help = "Check formatting without modifying files").flag()
  private val projectDir = File(System.getProperty("user.dir"))

  /** Factory for the underlying Gradle bridge. Tests override to inject a `FakeGradleBridge`. */
  protected open fun newGradleBridge(): GradleBridge = GradleBridge(projectDir, ui)

  override fun run() {
    try {
      runInternal()
    } finally {
      ui.endView()
    }
  }

  private fun runInternal() {
    ui.header(Versions.paperplaneVersion())

    val gradle = newGradleBridge()
    try {
      val spinMessage = if (check) "Checking formatting..." else "Formatting..."
      val result = ui.spin(spinMessage) { gradle.format(check = check) }

      ui.block {
        if (result.success) {
          success(if (check) "Formatting OK" else "Formatted")
        } else if (result.taskMissing) {
          error("No formatter configured for this project")
          status(
              "ppl format runs Spotless — add the Spotless plugin to build.gradle.kts to enable it"
          )
          status("See https://github.com/diffplug/spotless for setup")
        } else {
          val summary = result.rootMessage ?: "Format failed"
          error(summary)
          if (result.outputLines.isNotEmpty()) {
            buildError("Spotless output", null, result.outputLines.joinToString("\n"))
          }
        }
      }
    } finally {
      gradle.close()
    }
  }
}

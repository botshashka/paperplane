package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.util.formatDurationMs
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

open class BuildCommand(private val ui: TerminalUI) : CliktCommand(name = "build") {

  private val cleanFirst by option("--clean", help = "Run gradle clean before building").flag()

  private val output by
      option(
              "-o",
              "--output",
              help = "Copy the built jar to this directory after building",
          )
          .file(canBeFile = false)

  private val projectDir = File(System.getProperty("user.dir"))

  /** Factory for the underlying Gradle bridge. Tests override to inject a `FakeGradleBridge`. */
  protected open fun newGradleBridge(): GradleBridge = GradleBridge(projectDir, ui)

  override fun run() {
    val gradle = newGradleBridge()
    try {
      ui.block { runInBlock(gradle) }
    } finally {
      gradle.close()
      ui.endView()
    }
  }

  private fun runInBlock(gradle: GradleBridge) {
    val started = System.currentTimeMillis()

    if (cleanFirst) {
      val ok = ui.spin("Cleaning…") { gradle.clean() }
      if (!ok) {
        ui.error("Clean failed", formatDurationMs(System.currentTimeMillis() - started))
        return
      }
    }

    // ppMetadata transitively depends on shadowJar (or jar) via the gradle plugin, so this single
    // call both produces the deployable jar and tells us where it landed.
    val metadata = ui.spin("Building…") { gradle.metadata().metadataOrNull }
    val duration = formatDurationMs(System.currentTimeMillis() - started)

    if (metadata == null) {
      ui.error("Build failed", duration)
      return
    }

    val jar = File(metadata.projectDir, metadata.jarPath)
    ui.success("Built ${metadata.pluginName} ${metadata.version}", duration)
    ui.info("Output", relativeTo(jar))
    ui.info("Size", humanSize(jar.length()))

    val outputDir = output
    if (outputDir != null) {
      copyToOutput(jar, outputDir)
    }
  }

  private fun copyToOutput(jar: File, outputDir: File) {
    if (!outputDir.exists() && !outputDir.mkdirs()) {
      ui.error("Could not create output directory: ${outputDir.path}")
      return
    }
    if (!jar.exists()) {
      ui.error("Built jar not found at ${jar.path}")
      return
    }
    val target = File(outputDir, jar.name)
    Files.copy(jar.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    ui.info("Copied to", relativeTo(target))
  }

  private fun relativeTo(file: File): String {
    val cwd = File(System.getProperty("user.dir"))
    val rel = file.relativeToOrNull(cwd)
    // Fall back to absolute path when the file lives outside cwd — relativeTo would emit a chain of
    // "../../.." which is harder to read than the absolute path.
    return if (rel != null && !rel.path.startsWith("..")) rel.invariantSeparatorsPath
    else file.absolutePath
  }

  companion object {
    private const val BYTES_PER_KB = 1024.0
    private const val BYTES_PER_MB = 1024.0 * 1024.0

    private fun humanSize(bytes: Long): String =
        when {
          bytes >= BYTES_PER_MB -> "%.1f MB".format(bytes / BYTES_PER_MB)
          bytes >= BYTES_PER_KB -> "%.1f KB".format(bytes / BYTES_PER_KB)
          else -> "$bytes B"
        }
  }
}

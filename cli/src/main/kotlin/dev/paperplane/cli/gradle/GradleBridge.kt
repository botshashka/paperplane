package dev.paperplane.cli.gradle

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.paperplane.cli.ui.TerminalUI
import java.io.ByteArrayOutputStream
import java.io.File
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection

data class ProjectMetadata(
    val jarPath: String,
    val paperApiVersion: String,
    val mainClass: String,
    val pluginName: String,
    val projectDir: String,
    val version: String,
    val classesDir: String = "",
    val classesDirs: List<String> = emptyList(),
    val resourcesDir: String = "",
    val runtimeClasspath: List<String> = emptyList(),
) {
  val effectiveClassesDirs: List<String>
    get() =
        classesDirs.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(classesDir.takeIf { it.isNotEmpty() })
}

class GradleBridge(private val projectDir: File) : AutoCloseable {
  companion object {
    private const val MAX_DISPLAYED_ERRORS = 5
    private const val MAX_FALLBACK_LINES = 10
    internal val BUILD_ERROR_PATTERN = Regex("""(.+\.(?:java|kt)):(\d+): error: (.+)""")
  }

  private var connection: ProjectConnection? = null

  private fun connect(): ProjectConnection {
    if (connection == null) {
      connection = GradleConnector.newConnector().forProjectDirectory(projectDir).connect()
    }
    return connection!!
  }

  fun build(): Boolean = runTask("jar")

  fun compileOnly(): Boolean = runTask("classes")

  private fun runTask(taskName: String): Boolean {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    return try {
      connect()
          .newBuild()
          .forTasks(taskName)
          .setStandardOutput(stdout)
          .setStandardError(stderr)
          .run()
      true
    } catch (e: GradleConnectionException) {
      TerminalUI.status("Build failed: ${e.message}")
      val output = stderr.toString() + stdout.toString()
      parseBuildErrors(output)
      false
    }
  }

  fun test(quiet: Boolean = false, filter: String? = null): Boolean {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    return try {
      val args = mutableListOf("test")
      if (filter != null) {
        args.addAll(listOf("--tests", "*$filter*"))
      }
      connect()
          .newBuild()
          .withArguments(args)
          .setStandardOutput(stdout)
          .setStandardError(stderr)
          .run()
      true
    } catch (e: GradleConnectionException) {
      if (!quiet) {
        TerminalUI.status("Test failed: ${e.message}")
        val output = stderr.toString() + stdout.toString()
        parseBuildErrors(output)
      }
      false
    }
  }

  fun metadata(): ProjectMetadata? = runMetadataTask("ppMetadata")

  fun metadataFast(): ProjectMetadata? = runMetadataTask("ppMetadataFast")

  private fun runMetadataTask(taskName: String): ProjectMetadata? {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    return try {
      connect()
          .newBuild()
          .forTasks(taskName)
          .setStandardOutput(stdout)
          .setStandardError(stderr)
          .run()

      val metadataFile = File(projectDir, "build/paperplane/metadata.json")
      if (!metadataFile.exists()) return null

      parseMetadataFile(metadataFile)
    } catch (e: GradleConnectionException) {
      TerminalUI.status("Metadata task failed: ${e.message}")
      null
    }
  }

  private fun parseMetadataFile(metadataFile: File): ProjectMetadata? {
    val type = object : TypeToken<Map<String, Any>>() {}.type
    val map: Map<String, Any> = Gson().fromJson(metadataFile.readText(), type)
    return ProjectMetadata(
        jarPath = map["jarPath"] as? String ?: return null,
        paperApiVersion = map["paperApiVersion"] as? String ?: "unknown",
        mainClass = map["mainClass"] as? String ?: "unknown",
        pluginName = map["pluginName"] as? String ?: "unknown",
        projectDir = map["projectDir"] as? String ?: this.projectDir.absolutePath,
        version = map["version"] as? String ?: "unknown",
        classesDir = map["classesDir"] as? String ?: "",
        classesDirs = (map["classesDirs"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
        resourcesDir = map["resourcesDir"] as? String ?: "",
        runtimeClasspath =
            (map["runtimeClasspath"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
    )
  }

  private fun parseBuildErrors(output: String) {
    val errors = BUILD_ERROR_PATTERN.findAll(output).toList()

    if (errors.isNotEmpty()) {
      for (match in errors.take(MAX_DISPLAYED_ERRORS)) {
        val (file, line, message) = match.destructured
        val shortFile = file.substringAfter("src/")
        TerminalUI.buildError("src/$shortFile", line.toInt(), message)
      }
      if (errors.size > MAX_DISPLAYED_ERRORS) {
        TerminalUI.status("... and ${errors.size - MAX_DISPLAYED_ERRORS} more errors")
      }
    } else {
      // Fallback: show raw output (trimmed)
      val trimmed =
          output
              .lines()
              .filter { it.isNotBlank() }
              .filter { !it.startsWith(">") && !it.startsWith("*") }
              .takeLast(MAX_FALLBACK_LINES)
              .joinToString("\n")
      if (trimmed.isNotBlank()) {
        TerminalUI.buildError("Build output", null, trimmed)
      }
    }
  }

  override fun close() {
    connection?.close()
    connection = null
  }
}

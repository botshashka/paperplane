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
    private const val MAX_DISPLAYED_ERROR_LINES = 30
    internal val BUILD_ERROR_PATTERN = Regex("""(.+\.(?:java|kt)):(\d+): error: (.+)""")

    /**
     * True if [message] is a Gradle "task does not exist" error for [task]. Factored out so the
     * specific wordings (which can drift across Gradle versions) are covered by unit tests — if
     * Gradle changes its message, the tests break loudly instead of silently routing a missing-task
     * error down the generic "format failed" path.
     */
    internal fun isTaskNotFoundMessage(message: String?, task: String): Boolean {
      if (message == null) return false
      return message.contains("Task '$task' not found") ||
          message.contains("Cannot locate tasks that match '$task'") ||
          message.contains("Task '$task' is ambiguous") ||
          message.contains("task '$task' not found", ignoreCase = true)
    }
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

  data class FormatResult(
      val success: Boolean,
      val taskMissing: Boolean = false,
      val rootMessage: String? = null,
      val outputLines: List<String> = emptyList(),
  )

  fun format(check: Boolean = false): FormatResult {
    val task = if (check) "spotlessCheck" else "spotlessApply"
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    return try {
      connect().newBuild().forTasks(task).setStandardOutput(stdout).setStandardError(stderr).run()
      FormatResult(success = true)
    } catch (e: GradleConnectionException) {
      var root: Throwable = e
      while (root.cause != null && root.cause !== root) root = root.cause!!
      val rootMsg = root.message?.lines()?.firstOrNull { it.isNotBlank() }

      // Detecting "task not found" from the exception (rather than pre-loading the GradleProject
      // model via Tooling API) skips a full configuration phase on the happy path.
      if (isTaskNotFoundMessage(root.message, task) || isTaskNotFoundMessage(e.message, task)) {
        return FormatResult(success = false, taskMissing = true)
      }

      val combined = stderr.toString() + stdout.toString()
      val errorLines =
          combined
              .lines()
              .map { it.trimEnd() }
              .dropWhile {
                !it.startsWith("FAILURE") && !it.contains("error:") && !it.contains("Exception")
              }
              .filter { it.isNotBlank() }
              .take(MAX_DISPLAYED_ERROR_LINES)
      FormatResult(success = false, rootMessage = rootMsg, outputLines = errorLines)
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

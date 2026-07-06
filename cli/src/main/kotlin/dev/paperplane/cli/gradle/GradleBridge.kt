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
    val depend: List<String> = emptyList(),
    val softdepend: List<String> = emptyList(),
    val loadbefore: List<String> = emptyList(),
    val load: String = "POSTWORLD",
    val apiVersion: String = "",
) {
  val effectiveClassesDirs: List<String>
    get() =
        classesDirs.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(classesDir.takeIf { it.isNotEmpty() })
}

sealed class MetadataResult {
  data class Success(val metadata: ProjectMetadata) : MetadataResult()

  /** `ppMetadata` task does not exist — the PaperPlane gradle plugin is not applied. */
  object PluginNotApplied : MetadataResult()

  /**
   * `ppMetadata` exists but its task chain failed (e.g., a compile error in user source). Per-error
   * detail has already been surfaced via [GradleBridge.parseBuildErrors].
   */
  object TaskFailed : MetadataResult()

  val metadataOrNull: ProjectMetadata?
    get() = (this as? Success)?.metadata
}

open class GradleBridge(private val projectDir: File, private val ui: TerminalUI) : AutoCloseable {
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

  open fun build(): Boolean = runTask("jar")

  open fun clean(): Boolean = runTask("clean")

  open fun compileOnly(): Boolean = runTask("classes")

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
      ui.status("Build failed: ${rootCauseMessage(e)}")
      val output = stderr.toString() + stdout.toString()
      parseBuildErrors(output)
      false
    }
  }

  /**
   * Walks [e]'s cause chain to find the deepest non-null message. The Tooling API wraps every
   * failure in a generic `GradleConnectionException` whose message always references the
   * distribution URL ("Could not execute build using connection to Gradle distribution …"),
   * regardless of the actual cause. Walking the chain surfaces the real error (compile error,
   * missing task, configuration failure) instead of the misleading wrapper. Falls back to the outer
   * message if every cause is null.
   */
  private fun rootCauseMessage(e: Throwable): String {
    var root: Throwable = e
    while (root.cause != null && root.cause !== root) root = root.cause!!
    return root.message?.lines()?.firstOrNull { it.isNotBlank() } ?: e.message ?: "unknown error"
  }

  data class FormatResult(
      val success: Boolean,
      val taskMissing: Boolean = false,
      val rootMessage: String? = null,
      val outputLines: List<String> = emptyList(),
  )

  open fun format(check: Boolean = false): FormatResult {
    val task = if (check) "spotlessCheck" else "spotlessApply"
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    return try {
      connect().newBuild().forTasks(task).setStandardOutput(stdout).setStandardError(stderr).run()
      FormatResult(success = true)
    } catch (e: GradleConnectionException) {
      val rootMsg = rootCauseMessage(e)
      // Detecting "task not found" from the exception (rather than pre-loading the GradleProject
      // model via Tooling API) skips a full configuration phase on the happy path.
      if (isTaskNotFoundMessage(rootMsg, task) || isTaskNotFoundMessage(e.message, task)) {
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

  open fun test(quiet: Boolean = false, filter: String? = null): Boolean {
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
        ui.status("Test failed: ${rootCauseMessage(e)}")
        val output = stderr.toString() + stdout.toString()
        parseBuildErrors(output)
      }
      false
    }
  }

  open fun metadata(): MetadataResult = runMetadataTask("ppMetadata")

  open fun metadataFast(): MetadataResult = runMetadataTask("ppMetadataFast")

  private fun runMetadataTask(taskName: String): MetadataResult {
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
      if (!metadataFile.exists()) {
        ui.status("Metadata file not produced at build/paperplane/metadata.json")
        return MetadataResult.TaskFailed
      }

      parseMetadataFile(metadataFile)?.let { MetadataResult.Success(it) }
          ?: MetadataResult.TaskFailed
    } catch (e: GradleConnectionException) {
      val rootMsg = rootCauseMessage(e)
      // "no ppMetadata task" means the gradle plugin isn't applied (caller hint: ppl init);
      // any other gradle failure here is typically a compile error — recoverable by editing source.
      if (isTaskNotFoundMessage(rootMsg, taskName) || isTaskNotFoundMessage(e.message, taskName)) {
        return MetadataResult.PluginNotApplied
      }

      // Render compile errors so the user sees "MyPlugin.java:9: error: ';' expected"; the caller
      // frames the headline ("Build failed" with duration).
      val output = stderr.toString() + stdout.toString()
      parseBuildErrors(output)
      MetadataResult.TaskFailed
    }
  }

  private fun parseMetadataFile(metadataFile: File): ProjectMetadata? {
    val type = object : TypeToken<Map<String, Any>>() {}.type
    val map: Map<String, Any> = Gson().fromJson(metadataFile.readText(), type)
    return ProjectMetadata(
        jarPath = map["jarPath"] as? String ?: return null,
        paperApiVersion = map.str("paperApiVersion", "unknown"),
        mainClass = map.str("mainClass", "unknown"),
        pluginName = map.str("pluginName", "unknown"),
        projectDir = map.str("projectDir", this.projectDir.absolutePath),
        version = map.str("version", "unknown"),
        classesDir = map.str("classesDir", ""),
        classesDirs = map.strList("classesDirs"),
        resourcesDir = map.str("resourcesDir", ""),
        runtimeClasspath = map.strList("runtimeClasspath"),
        depend = map.strList("depend"),
        softdepend = map.strList("softdepend"),
        loadbefore = map.strList("loadbefore"),
        load = map.str("load", "POSTWORLD"),
        apiVersion = map.str("apiVersion", ""),
    )
  }

  private fun Map<String, Any>.str(key: String, default: String): String =
      this[key] as? String ?: default

  private fun Map<String, Any>.strList(key: String): List<String> =
      (this[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

  private fun parseBuildErrors(output: String) {
    // Dedupe by (file, line, message) — Gradle prints the same compile error twice (once in the
    // ":compileJava FAILED" output section and once in the indented "* What went wrong" section).
    // The regex isn't anchored, so the indented occurrence captures a file path with leading
    // whitespace. Normalize with trim() before keying.
    val seen = LinkedHashMap<Triple<String, String, String>, MatchResult>()
    for (match in BUILD_ERROR_PATTERN.findAll(output)) {
      val (file, line, message) = match.destructured
      seen.putIfAbsent(Triple(file.trim(), line, message.trim()), match)
    }
    val errors = seen.values.toList()

    if (errors.isNotEmpty()) {
      for (match in errors.take(MAX_DISPLAYED_ERRORS)) {
        val (file, line, message) = match.destructured
        val shortFile = file.substringAfter("src/")
        ui.buildError("src/$shortFile", line.toInt(), message)
      }
      if (errors.size > MAX_DISPLAYED_ERRORS) {
        ui.status("... and ${errors.size - MAX_DISPLAYED_ERRORS} more errors")
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
        ui.buildError("Build output", null, trimmed)
      }
    }
  }

  override fun close() = doClose()

  /** Hook for subclasses (test fakes) to override without dealing with `final override fun`. */
  protected open fun doClose() {
    connection?.close()
    connection = null
  }
}

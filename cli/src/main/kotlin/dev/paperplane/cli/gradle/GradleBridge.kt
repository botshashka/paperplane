package dev.paperplane.cli.gradle

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.paperplane.cli.ui.TerminalUI
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import java.io.ByteArrayOutputStream
import java.io.File

data class ProjectMetadata(
    val jarPath: String,
    val paperApiVersion: String,
    val mainClass: String,
    val pluginName: String,
    val projectDir: String,
    val version: String
)

class GradleBridge(private val projectDir: File) : AutoCloseable {
    private var connection: ProjectConnection? = null

    private fun connect(): ProjectConnection {
        if (connection == null) {
            connection = GradleConnector.newConnector()
                .forProjectDirectory(projectDir)
                .connect()
        }
        return connection!!
    }

    fun build(): Boolean {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        return try {
            connect().newBuild()
                .forTasks("jar")
                .setStandardOutput(stdout)
                .setStandardError(stderr)
                .run()
            true
        } catch (e: Exception) {
            val output = stderr.toString() + stdout.toString()
            parseBuildErrors(output)
            false
        }
    }

    fun test(): Boolean {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        return try {
            connect().newBuild()
                .forTasks("test")
                .setStandardOutput(stdout)
                .setStandardError(stderr)
                .run()
            true
        } catch (e: Exception) {
            val output = stderr.toString() + stdout.toString()
            parseBuildErrors(output)
            false
        }
    }

    fun metadata(): ProjectMetadata? {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        return try {
            connect().newBuild()
                .forTasks("ppMetadata")
                .setStandardOutput(stdout)
                .setStandardError(stderr)
                .run()

            val metadataFile = File(projectDir, "build/paperplane/metadata.json")
            if (!metadataFile.exists()) return null

            val type = object : TypeToken<Map<String, String>>() {}.type
            val map: Map<String, String> = Gson().fromJson(metadataFile.readText(), type)
            ProjectMetadata(
                jarPath = map["jarPath"] ?: return null,
                paperApiVersion = map["paperApiVersion"] ?: "unknown",
                mainClass = map["mainClass"] ?: "unknown",
                pluginName = map["pluginName"] ?: "unknown",
                projectDir = map["projectDir"] ?: projectDir.absolutePath,
                version = map["version"] ?: "unknown"
            )
        } catch (e: Exception) {
            TerminalUI.error("Failed to read project metadata: ${e.message}")
            null
        }
    }

    private fun parseBuildErrors(output: String) {
        // Extract Java/Kotlin compiler errors and display them nicely
        val errorPattern = Regex("""(.+\.(?:java|kt)):(\d+): error: (.+)""")
        val errors = errorPattern.findAll(output).toList()

        if (errors.isNotEmpty()) {
            for (match in errors.take(5)) {
                val (file, line, message) = match.destructured
                val shortFile = file.substringAfter("src/")
                TerminalUI.buildError("src/$shortFile", line.toInt(), message)
            }
            if (errors.size > 5) {
                TerminalUI.status("... and ${errors.size - 5} more errors")
            }
        } else {
            // Fallback: show raw output (trimmed)
            val trimmed = output.lines()
                .filter { it.isNotBlank() }
                .filter { !it.startsWith(">") && !it.startsWith("*") }
                .takeLast(10)
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

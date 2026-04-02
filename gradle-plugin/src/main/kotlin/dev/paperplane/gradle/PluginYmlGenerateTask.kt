package dev.paperplane.gradle

import org.gradle.api.DefaultTask
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

@DisableCachingByDefault(because = "Fast task, not worth caching")
abstract class PluginYmlGenerateTask : DefaultTask() {
    @get:Internal
    lateinit var extension: PaperPlaneExtension

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val lines = mutableListOf<String>()

        lines.add("name: ${extension.pluginName.get()}")

        if (extension.mainClass.isPresent) {
            lines.add("main: ${extension.mainClass.get()}")
        } else {
            throw IllegalStateException(
                "PaperPlane: mainClass must be set in the paperplane { } block or a manual plugin.yml must exist."
            )
        }

        lines.add("version: ${project.version}")

        if (extension.apiVersion.isPresent) {
            lines.add("api-version: ${extension.apiVersion.get()}")
        }

        if (extension.description.isPresent) {
            lines.add("description: ${extension.description.get()}")
        }

        if (extension.authors.isPresent && extension.authors.get().isNotEmpty()) {
            val authorList = extension.authors.get().joinToString(", ") { "'$it'" }
            lines.add("authors: [$authorList]")
        }

        if (extension.website.isPresent) {
            lines.add("website: ${extension.website.get()}")
        }

        if (extension.depend.isPresent && extension.depend.get().isNotEmpty()) {
            val depList = extension.depend.get().joinToString(", ") { it }
            lines.add("depend: [$depList]")
        }

        if (extension.softDepend.isPresent && extension.softDepend.get().isNotEmpty()) {
            val depList = extension.softDepend.get().joinToString(", ") { it }
            lines.add("softdepend: [$depList]")
        }

        // Commands
        if (extension.commands.isNotEmpty()) {
            lines.add("commands:")
            for (cmd in extension.commands) {
                lines.add("  ${cmd.name}:")
                if (cmd.description.isPresent) {
                    lines.add("    description: ${cmd.description.get()}")
                }
                if (cmd.usage.isPresent) {
                    lines.add("    usage: ${cmd.usage.get()}")
                }
                if (cmd.aliases.isPresent && cmd.aliases.get().isNotEmpty()) {
                    val aliasList = cmd.aliases.get().joinToString(", ")
                    lines.add("    aliases: [$aliasList]")
                }
                if (cmd.permission.isPresent) {
                    lines.add("    permission: ${cmd.permission.get()}")
                }
            }
        }

        // Permissions
        if (extension.permissions.isNotEmpty()) {
            lines.add("permissions:")
            for (perm in extension.permissions) {
                lines.add("  ${perm.name}:")
                if (perm.default.isPresent) {
                    lines.add("    default: ${perm.default.get()}")
                }
                if (perm.description.isPresent) {
                    lines.add("    description: ${perm.description.get()}")
                }
            }
        }

        val outFile = File(outputDir.get().asFile, "plugin.yml")
        outFile.parentFile.mkdirs()
        outFile.writeText(lines.joinToString("\n") + "\n")

        logger.lifecycle("PaperPlane: Generated plugin.yml")
    }
}

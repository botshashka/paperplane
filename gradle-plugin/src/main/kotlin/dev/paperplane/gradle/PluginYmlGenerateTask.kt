package dev.paperplane.gradle

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Fast task, not worth caching")
abstract class PluginYmlGenerateTask : DefaultTask() {
  @get:Input abstract val pluginName: Property<String>
  @get:Input @get:Optional abstract val mainClass: Property<String>
  @get:Input abstract val projectVersion: Property<String>
  @get:Input @get:Optional abstract val apiVersion: Property<String>
  @get:Input @get:Optional abstract val pluginDescription: Property<String>
  @get:Input @get:Optional abstract val authors: ListProperty<String>
  @get:Input @get:Optional abstract val website: Property<String>
  @get:Input @get:Optional abstract val depend: ListProperty<String>
  @get:Input @get:Optional abstract val softDepend: ListProperty<String>
  @get:Nested abstract val commands: ListProperty<CommandInput>
  @get:Nested abstract val permissions: ListProperty<PermissionInput>

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    if (!mainClass.isPresent) {
      throw IllegalStateException(
          "PaperPlane: mainClass must be set in the paperplane { } block or a manual plugin.yml must exist."
      )
    }

    val lines = mutableListOf<String>()
    lines.add("name: ${pluginName.get()}")
    lines.add("main: ${mainClass.get()}")
    lines.add("version: ${projectVersion.get()}")
    apiVersion.orNull?.let { lines.add("api-version: $it") }
    pluginDescription.orNull?.let { lines.add("description: $it") }

    val authorList = authors.orNull.orEmpty()
    if (authorList.isNotEmpty()) {
      lines.add("authors: [${authorList.joinToString(", ")}]")
    }
    website.orNull?.let { lines.add("website: $it") }

    val depList = depend.orNull.orEmpty()
    if (depList.isNotEmpty()) {
      lines.add("depend: [${depList.joinToString(", ")}]")
    }
    val softDepList = softDepend.orNull.orEmpty()
    if (softDepList.isNotEmpty()) {
      lines.add("softdepend: [${softDepList.joinToString(", ")}]")
    }

    val cmds = commands.get()
    if (cmds.isNotEmpty()) {
      lines.add("commands:")
      for (cmd in cmds) {
        lines.add("  ${cmd.commandName.get()}:")
        cmd.description.orNull?.let { lines.add("    description: $it") }
        cmd.usage.orNull?.let { lines.add("    usage: $it") }
        val aliases = cmd.aliases.orNull.orEmpty()
        if (aliases.isNotEmpty()) {
          lines.add("    aliases: [${aliases.joinToString(", ")}]")
        }
        cmd.permission.orNull?.let { lines.add("    permission: $it") }
      }
    }

    val perms = permissions.get()
    if (perms.isNotEmpty()) {
      lines.add("permissions:")
      for (perm in perms) {
        lines.add("  ${perm.permissionName.get()}:")
        perm.default.orNull?.let { lines.add("    default: $it") }
        perm.description.orNull?.let { lines.add("    description: $it") }
        val children = perm.children.orNull.orEmpty()
        if (children.isNotEmpty()) {
          lines.add("    children:")
          for ((child, value) in children) {
            lines.add("      $child: $value")
          }
        }
      }
    }

    val outFile = File(outputDir.get().asFile, "plugin.yml")
    outFile.parentFile.mkdirs()
    outFile.writeText(lines.joinToString("\n") + "\n")

    logger.lifecycle("PaperPlane: Generated plugin.yml")
  }
}

interface CommandInput {
  @get:Input val commandName: Property<String>
  @get:Input @get:Optional val description: Property<String>
  @get:Input @get:Optional val usage: Property<String>
  @get:Input val aliases: ListProperty<String>
  @get:Input @get:Optional val permission: Property<String>
}

interface PermissionInput {
  @get:Input val permissionName: Property<String>
  @get:Input @get:Optional val default: Property<String>
  @get:Input @get:Optional val description: Property<String>
  @get:Input val children: MapProperty<String, Boolean>
}

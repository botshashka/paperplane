package dev.paperplane.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class PaperPlanePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension =
        project.extensions.create("paperplane", PaperPlaneExtension::class.java, project)

    project.tasks.register("ppGeneratePluginYml", PluginYmlGenerateTask::class.java) { task ->
      task.group = "paperplane"
      task.description = "Generates plugin.yml from PaperPlane extension configuration"
      task.extension = extension
      task.outputDir.set(project.layout.buildDirectory.dir("generated/paperplane"))
    }

    project.tasks.register("ppMetadata", MetadataTask::class.java) { task ->
      task.group = "paperplane"
      task.description = "Writes project metadata for the PaperPlane CLI"
      task.extension = extension
      task.outputDir.set(project.layout.buildDirectory.dir("paperplane"))
    }

    // Wire plugin.yml generation into processResources
    project.afterEvaluate {
      val manualPluginYml = project.file("src/main/resources/plugin.yml")
      if (!manualPluginYml.exists()) {
        val generateTask = project.tasks.named("ppGeneratePluginYml")
        project.tasks.named("processResources") { task ->
          task.dependsOn(generateTask)
          (task as org.gradle.language.jvm.tasks.ProcessResources).from(
              project.layout.buildDirectory.dir("generated/paperplane")
          )
        }
      } else {
        project.logger.warn("PaperPlane: Manual plugin.yml found, skipping generation.")
      }

      // Auto-detect Paper API version from dependencies
      if (!extension.apiVersion.isPresent) {
        val paperVersion = detectPaperApiVersion(project)
        if (paperVersion != null) {
          extension.apiVersion.set(paperVersion)
        }
      }

      // Auto-detect main class if not set
      if (!extension.mainClass.isPresent) {
        // Will be resolved at task execution time by scanning compiled classes
      }
    }

    project.tasks.register("ppMetadataFast", MetadataTask::class.java) { task ->
      task.group = "paperplane"
      task.description = "Writes project metadata for HMR (skips jar packaging)"
      task.extension = extension
      task.outputDir.set(project.layout.buildDirectory.dir("paperplane"))
    }

    // Make ppMetadata run after compileJava/compileKotlin so jar path is known
    project.tasks.named("ppMetadata") { task -> task.dependsOn("jar") }

    project.tasks.named("ppMetadataFast") { task -> task.dependsOn("classes") }
  }

  private fun detectPaperApiVersion(project: Project): String? {
    val configs = listOf("compileOnly", "implementation", "compileClasspath")
    for (configName in configs) {
      val config = project.configurations.findByName(configName) ?: continue
      for (dep in config.dependencies) {
        if (dep.group == "io.papermc.paper" && dep.name == "paper-api") {
          val version = dep.version ?: continue
          // Parse "1.21.4-R0.1-SNAPSHOT" → "1.21"
          val match = Regex("""^(\d+\.\d+)""").find(version)
          return match?.groupValues?.get(1)
        }
      }
    }
    return null
  }
}

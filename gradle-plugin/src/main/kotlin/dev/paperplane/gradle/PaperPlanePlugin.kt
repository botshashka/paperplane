package dev.paperplane.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar

class PaperPlanePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension =
        project.extensions.create("paperplane", PaperPlaneExtension::class.java, project)

    project.tasks.register("ppGeneratePluginYml", PluginYmlGenerateTask::class.java) { task ->
      task.group = "paperplane"
      task.description = "Generates plugin.yml from PaperPlane extension configuration"
      wirePluginYmlInputs(project, extension, task)
      task.outputDir.set(project.layout.buildDirectory.dir("generated/paperplane"))
    }

    project.tasks.register("ppMetadata", MetadataTask::class.java) { task ->
      task.group = "paperplane"
      task.description = "Writes project metadata for the PaperPlane CLI"
      wireMetadataInputs(project, extension, task)
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

      // Make ppMetadata run after jar packaging so jar path is known.
      // Prefer shadowJar (fat jar) when available — ensures bundled dependencies are deployed.
      val jarDep = if (project.tasks.findByName("shadowJar") != null) "shadowJar" else "jar"
      project.tasks.named("ppMetadata") { task -> task.dependsOn(jarDep) }

      configureMappingsNamespace(project)
    }

    project.tasks.register("ppMetadataFast", MetadataTask::class.java) { task ->
      task.group = "paperplane"
      task.description = "Writes project metadata for HMR (skips jar packaging)"
      wireMetadataInputs(project, extension, task)
      task.outputDir.set(project.layout.buildDirectory.dir("paperplane"))
    }

    project.tasks.named("ppMetadataFast") { task -> task.dependsOn("classes") }
  }

  private fun wirePluginYmlInputs(
      project: Project,
      extension: PaperPlaneExtension,
      task: PluginYmlGenerateTask,
  ) {
    task.pluginName.set(extension.pluginName)
    task.mainClass.set(extension.mainClass)
    task.projectVersion.set(project.provider { project.version.toString() })
    task.apiVersion.set(extension.apiVersion)
    task.pluginDescription.set(extension.description)
    task.authors.set(extension.authors)
    task.website.set(extension.website)
    task.depend.set(extension.depend)
    task.softDepend.set(extension.softDepend)

    val objects = project.objects
    task.commands.set(
        project.provider {
          extension.commands.map { cmd ->
            objects.newInstance(CommandInput::class.java).apply {
              commandName.set(cmd.name)
              description.set(cmd.description)
              usage.set(cmd.usage)
              aliases.set(cmd.aliases)
              permission.set(cmd.permission)
            }
          }
        }
    )
    task.permissions.set(
        project.provider {
          extension.permissions.map { perm ->
            objects.newInstance(PermissionInput::class.java).apply {
              permissionName.set(perm.name)
              default.set(perm.default)
              description.set(perm.description)
              children.set(perm.children)
            }
          }
        }
    )
  }

  private fun wireMetadataInputs(
      project: Project,
      extension: PaperPlaneExtension,
      task: MetadataTask,
  ) {
    task.pluginName.set(extension.pluginName)
    task.mainClass.set(extension.mainClass)
    task.apiVersion.set(extension.apiVersion)
    task.projectVersion.set(project.provider { project.version.toString() })
    task.depend.set(extension.depend)
    task.softDepend.set(extension.softDepend)
  }

  /**
   * Declares the jar Mojang-mapped, so Paper 1.20.5+ skips its load-time remap. That keeps loads
   * fast and keeps loaded bytecode byte-identical to the build output — which the instant tier's
   * loaded-CRC verification depends on in native modes. `withType` covers shadowJar (it extends
   * Jar).
   *
   * Gated, because the attribute is a **claim about the bytecode** and it rides into whatever the
   * project publishes. `ppl init` applies this plugin to any project it is pointed at, so stamping
   * unconditionally would mislabel a Spigot-mapped or reobf'd jar: Paper would skip a remap the jar
   * actually needs and NMS calls would fail at runtime — in the user's release build, not just
   * under `ppl dev`. Only a project compiling against paper-api is known Mojang-mapped, and
   * paperweight-userdev manages the attribute itself.
   */
  private fun configureMappingsNamespace(project: Project) {
    if (project.plugins.hasPlugin("io.papermc.paperweight.userdev")) return
    if (detectPaperApiVersion(project) == null) return
    project.tasks.withType(Jar::class.java).configureEach { jar ->
      jar.manifest.attributes["paperweight-mappings-namespace"] = "mojang"
    }
  }

  /**
   * The Paper API minor version (`1.21.4-R0.1-SNAPSHOT` → `1.21`) this project compiles against, or
   * null when it declares none.
   *
   * `allDependencies` rather than `dependencies`: the latter holds only what was declared
   * *directly* on a configuration and does not traverse `extendsFrom`, so a `java-library` project
   * declaring paper-api via `api`/`compileOnlyApi` would read as no-Paper-at-all. That silently
   * costs the mappings stamp — Paper then remaps the jar at load and the instant lane refuses every
   * patch as baseline drift — as well as the [PaperPlaneExtension.apiVersion] default. The named
   * `api`/`compileOnlyApi` entries are belt and braces for the configurations that carry the
   * declaration before resolution.
   */
  private fun detectPaperApiVersion(project: Project): String? {
    val configs =
        listOf("compileOnly", "compileOnlyApi", "api", "implementation", "compileClasspath")
    for (configName in configs) {
      val config = project.configurations.findByName(configName) ?: continue
      for (dep in config.allDependencies) {
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

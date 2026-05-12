package dev.paperplane.gradle

import com.google.gson.GsonBuilder
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Metadata changes every build")
abstract class MetadataTask : DefaultTask() {
  @get:Input abstract val pluginName: Property<String>
  @get:Input @get:Optional abstract val mainClass: Property<String>
  @get:Input @get:Optional abstract val apiVersion: Property<String>
  @get:Input abstract val projectVersion: Property<String>
  @get:Input @get:Optional abstract val depend: ListProperty<String>
  @get:Input @get:Optional abstract val softDepend: ListProperty<String>

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun writeMetadata() {
    // Prefer shadowJar output (fat jar with bundled dependencies) over plain jar.
    // This ensures Kotlin runtime and other dependencies are included when deployed.
    val shadowJarTask = project.tasks.findByName("shadowJar") as? org.gradle.jvm.tasks.Jar
    val jarTask =
        shadowJarTask
            ?: project.tasks.findByName("jar") as? org.gradle.jvm.tasks.Jar
            ?: throw IllegalStateException(
                "PaperPlane: No 'jar' task found. Ensure the Java or Kotlin plugin is applied."
            )
    val jarPath = jarTask.archiveFile.get().asFile.relativeTo(project.projectDir).path

    // Use Gradle's source set API to find actual class output directories.
    // This works for Java, Kotlin, and mixed projects — each language plugin
    // adds its output directory to the main source set's classesDirs.
    val mainSourceSet =
        project.extensions
            .getByType(JavaPluginExtension::class.java)
            .sourceSets
            .getByName(SourceSet.MAIN_SOURCE_SET_NAME)
    val classesDirs =
        mainSourceSet.output.classesDirs.files.filter { it.exists() }.map { it.absolutePath }
    val classesDir =
        classesDirs.firstOrNull()
            ?: project.layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath
    val resourcesDir =
        mainSourceSet.output.resourcesDir?.absolutePath
            ?: project.layout.buildDirectory.dir("resources/main").get().asFile.absolutePath
    val runtimeJars =
        try {
          project.configurations.getByName("runtimeClasspath").resolve().map { it.absolutePath }
        } catch (_: Exception) {
          emptyList<String>()
        }

    // depend/softdepend feed CompanionJarRewriter so the companion's plugin.yml inherits them —
    // Paper resolves load order at boot time from plugins/, but the user plugin lives in
    // .paperplane/staged/ now, so the companion has to claim those depends on its behalf.
    val metadata =
        mapOf(
            "jarPath" to jarPath,
            "paperApiVersion" to (apiVersion.orNull ?: "unknown"),
            "mainClass" to (mainClass.orNull ?: "unknown"),
            "pluginName" to pluginName.get(),
            "projectDir" to project.projectDir.absolutePath,
            "version" to projectVersion.get(),
            "classesDir" to classesDir,
            "classesDirs" to classesDirs,
            "resourcesDir" to resourcesDir,
            "runtimeClasspath" to runtimeJars,
            "depend" to (depend.orNull ?: emptyList<String>()),
            "softdepend" to (softDepend.orNull ?: emptyList<String>()),
            "loadbefore" to emptyList<String>(),
            "load" to "POSTWORLD",
            "apiVersion" to (apiVersion.orNull ?: ""),
        )

    val outFile = File(outputDir.get().asFile, "metadata.json")
    outFile.parentFile.mkdirs()

    val gson = GsonBuilder().setPrettyPrinting().create()
    outFile.writeText(gson.toJson(metadata))

    logger.lifecycle("PaperPlane: Wrote metadata to ${outFile.path}")
  }
}

package dev.paperplane.gradle

import com.google.gson.GsonBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import java.io.File

@DisableCachingByDefault(because = "Metadata changes every build")
abstract class MetadataTask : DefaultTask() {
    @get:Internal
    lateinit var extension: PaperPlaneExtension

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun writeMetadata() {
        val jarTask = project.tasks.named("jar").get() as org.gradle.jvm.tasks.Jar
        val jarPath = jarTask.archiveFile.get().asFile.relativeTo(project.projectDir).path

        // Use Gradle's source set API to find actual class output directories.
        // This works for Java, Kotlin, and mixed projects — each language plugin
        // adds its output directory to the main source set's classesDirs.
        val mainSourceSet = project.extensions.getByType(JavaPluginExtension::class.java)
            .sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val classesDirs = mainSourceSet.output.classesDirs.files
            .filter { it.exists() }
            .map { it.absolutePath }
        val classesDir = classesDirs.firstOrNull()
            ?: project.layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath
        val resourcesDir = mainSourceSet.output.resourcesDir?.absolutePath
            ?: project.layout.buildDirectory.dir("resources/main").get().asFile.absolutePath
        val runtimeJars = try {
            project.configurations.getByName("runtimeClasspath")
                .resolve().map { it.absolutePath }
        } catch (_: Exception) { emptyList<String>() }

        val metadata = mapOf(
            "jarPath" to jarPath,
            "paperApiVersion" to (extension.apiVersion.orNull ?: "unknown"),
            "mainClass" to (extension.mainClass.orNull ?: "unknown"),
            "pluginName" to extension.pluginName.get(),
            "projectDir" to project.projectDir.absolutePath,
            "version" to project.version.toString(),
            "classesDir" to classesDir,
            "classesDirs" to classesDirs,
            "resourcesDir" to resourcesDir,
            "runtimeClasspath" to runtimeJars
        )

        val outFile = File(outputDir.get().asFile, "metadata.json")
        outFile.parentFile.mkdirs()

        val gson = GsonBuilder().setPrettyPrinting().create()
        outFile.writeText(gson.toJson(metadata))

        logger.lifecycle("PaperPlane: Wrote metadata to ${outFile.path}")
    }
}

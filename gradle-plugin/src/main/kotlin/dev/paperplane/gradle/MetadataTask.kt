package dev.paperplane.gradle

import com.google.gson.GsonBuilder
import org.gradle.api.DefaultTask
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
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

        val classesDir = project.layout.buildDirectory.dir("classes/kotlin/main").get().asFile.absolutePath
        val resourcesDir = project.layout.buildDirectory.dir("resources/main").get().asFile.absolutePath
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

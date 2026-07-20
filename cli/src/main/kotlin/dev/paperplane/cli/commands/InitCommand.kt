package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import dev.paperplane.cli.Versions
import dev.paperplane.cli.server.PaperVersionResolver
import dev.paperplane.cli.ui.TerminalUI
import java.io.File

class InitCommand(private val ui: TerminalUI) : CliktCommand(name = "init") {
  companion object {
    private val PAPER_VERSION_PATTERN = Regex("""paper-api[:'"]([^'"]+)-R""")
  }

  private val projectDir = File(System.getProperty("user.dir"))

  override fun run() {
    try {
      runInternal()
    } finally {
      ui.endView()
    }
  }

  private fun runInternal() {
    val version = Versions.paperplaneVersion()
    ui.header(version)

    val buildFile = findBuildFile()
    if (buildFile == null) {
      ui.block { error("No build.gradle or build.gradle.kts found in current directory") }
      return
    }

    ui.block {
      status("Found ${buildFile.name}")
      val buildContent = buildFile.readText()
      ensurePluginApplied(buildFile, buildContent, version)
      ensureMavenLocal()
      ensurePaperplaneYml(buildContent)
      ensureGitignoreEntry()
    }

    ui.block {
      success("PaperPlane setup complete")
      status("Run 'ppl dev' to start developing!")
    }
  }

  private fun findBuildFile(): File? {
    val kts = File(projectDir, "build.gradle.kts")
    if (kts.exists()) return kts
    val groovy = File(projectDir, "build.gradle")
    if (groovy.exists()) return groovy
    return null
  }

  private fun TerminalUI.ensurePluginApplied(
      buildFile: File,
      buildContent: String,
      version: String,
  ) {
    if (buildContent.contains("dev.paperplane")) {
      status("PaperPlane plugin already applied")
      return
    }
    val isKts = buildFile.name.endsWith(".kts")
    val pluginLine =
        if (isKts) """    id("dev.paperplane") version "$version""""
        else """    id 'dev.paperplane' version '$version'"""
    val updated =
        if (buildContent.contains("plugins {")) {
          buildContent.replaceFirst("plugins {", "plugins {\n$pluginLine")
        } else {
          "plugins {\n$pluginLine\n}\n\n$buildContent"
        }
    buildFile.writeText(updated)
    fileCreated("Added PaperPlane plugin to ${buildFile.name}")
  }

  private fun TerminalUI.ensureMavenLocal() {
    val settingsFile =
        File(projectDir, "settings.gradle.kts").takeIf { it.exists() }
            ?: File(projectDir, "settings.gradle").takeIf { it.exists() }
            ?: return
    if (!ProjectTemplates.gradlePluginInMavenLocal()) return
    val settingsContent = settingsFile.readText()
    if (settingsContent.contains("mavenLocal")) return
    settingsFile.writeText(ProjectTemplates.PLUGIN_MANAGEMENT_BLOCK + settingsContent)
    fileCreated("Added mavenLocal() to ${settingsFile.name}")
  }

  private fun TerminalUI.ensurePaperplaneYml(buildContent: String) {
    val configFile = File(projectDir, "paperplane.yml")
    if (configFile.exists()) {
      status("paperplane.yml already exists")
      return
    }
    val match = PAPER_VERSION_PATTERN.find(buildContent)
    val detectedVersion = match?.groupValues?.get(1) ?: PaperVersionResolver().resolveLatest()
    configFile.writeText(
        ProjectTemplates.paperplaneYml(detectedVersion, DevMode.HOT_RELOAD.value, "auto")
    )
    fileCreated("paperplane.yml")
  }

  private fun TerminalUI.ensureGitignoreEntry() {
    File(projectDir, ".paperplane").mkdirs()
    val gitignore = File(projectDir, ".gitignore")
    if (!gitignore.exists()) return
    val gitignoreContent = gitignore.readText()
    if (gitignoreContent.contains(".paperplane")) return
    gitignore.appendText("\n# PaperPlane\n.paperplane/\n")
    fileCreated("Added .paperplane/ to .gitignore")
  }
}

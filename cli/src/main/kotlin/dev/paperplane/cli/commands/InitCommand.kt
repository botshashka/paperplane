package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import dev.paperplane.cli.Versions
import dev.paperplane.cli.server.PaperVersionResolver
import dev.paperplane.cli.ui.TerminalUI
import java.io.File

class InitCommand : CliktCommand(name = "init") {
  companion object {
    private val PAPER_VERSION_PATTERN = Regex("""paper-api[:'"]([^'"]+)-R""")
  }

  private val projectDir = File(System.getProperty("user.dir"))

  override fun run() {
    try {
      runInternal()
    } finally {
      TerminalUI.endView()
    }
  }

  private fun runInternal() {
    val version = Versions.paperplaneVersion()
    TerminalUI.header(version)

    val buildFileKts = File(projectDir, "build.gradle.kts")
    val buildFileGroovy = File(projectDir, "build.gradle")

    val buildFile =
        when {
          buildFileKts.exists() -> buildFileKts
          buildFileGroovy.exists() -> buildFileGroovy
          else -> {
            TerminalUI.block {
              error("No build.gradle or build.gradle.kts found in current directory")
            }
            return
          }
        }

    val isKts = buildFile.name.endsWith(".kts")

    TerminalUI.block {
      status("Found ${buildFile.name}")

      val buildContent = buildFile.readText()
      if (buildContent.contains("dev.paperplane")) {
        status("PaperPlane plugin already applied")
      } else {
        val pluginLine =
            if (isKts) {
              """    id("dev.paperplane") version "$version""""
            } else {
              """    id 'dev.paperplane' version '$version'"""
            }

        val updated =
            if (buildContent.contains("plugins {")) {
              buildContent.replaceFirst("plugins {", "plugins {\n$pluginLine")
            } else {
              "plugins {\n$pluginLine\n}\n\n$buildContent"
            }
        buildFile.writeText(updated)
        fileCreated("Added PaperPlane plugin to ${buildFile.name}")
      }

      val settingsKts = File(projectDir, "settings.gradle.kts")
      val settingsGroovy = File(projectDir, "settings.gradle")
      val settingsFile =
          when {
            settingsKts.exists() -> settingsKts
            settingsGroovy.exists() -> settingsGroovy
            else -> null
          }

      if (settingsFile != null && ProjectTemplates.gradlePluginInMavenLocal()) {
        val settingsContent = settingsFile.readText()
        if (!settingsContent.contains("mavenLocal")) {
          settingsFile.writeText(ProjectTemplates.PLUGIN_MANAGEMENT_BLOCK + settingsContent)
          fileCreated("Added mavenLocal() to ${settingsFile.name}")
        }
      }

      val match = PAPER_VERSION_PATTERN.find(buildContent)
      val detectedVersion = match?.groupValues?.get(1) ?: PaperVersionResolver().resolveLatest()

      val configFile = File(projectDir, "paperplane.yml")
      if (!configFile.exists()) {
        configFile.writeText(ProjectTemplates.paperplaneYml(detectedVersion, "hot-reload", "auto"))
        fileCreated("paperplane.yml")
      } else {
        status("paperplane.yml already exists")
      }

      File(projectDir, ".paperplane").mkdirs()
      val gitignore = File(projectDir, ".gitignore")
      if (gitignore.exists()) {
        val gitignoreContent = gitignore.readText()
        if (!gitignoreContent.contains(".paperplane")) {
          gitignore.appendText("\n# PaperPlane\n.paperplane/\n")
          fileCreated("Added .paperplane/ to .gitignore")
        }
      }
    }

    TerminalUI.block {
      success("PaperPlane setup complete")
      status("Run 'ppl dev' to start developing!")
    }
  }
}

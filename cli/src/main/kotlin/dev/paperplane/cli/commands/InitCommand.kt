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

    // Detect build file
    val buildFileKts = File(projectDir, "build.gradle.kts")
    val buildFileGroovy = File(projectDir, "build.gradle")

    val buildFile =
        when {
          buildFileKts.exists() -> buildFileKts
          buildFileGroovy.exists() -> buildFileGroovy
          else -> {
            TerminalUI.beginBlock()
            TerminalUI.error("No build.gradle or build.gradle.kts found in current directory")
            TerminalUI.endBlock()
            return
          }
        }

    val isKts = buildFile.name.endsWith(".kts")

    TerminalUI.beginBlock()
    TerminalUI.status("Found ${buildFile.name}")

    // Add plugin to build file if not already present
    val buildContent = buildFile.readText()
    if (buildContent.contains("dev.paperplane")) {
      TerminalUI.status("PaperPlane plugin already applied")
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
      TerminalUI.fileCreated("Added PaperPlane plugin to ${buildFile.name}")
    }

    // Add pluginManagement to settings if needed
    val settingsKts = File(projectDir, "settings.gradle.kts")
    val settingsGroovy = File(projectDir, "settings.gradle")
    val settingsFile =
        when {
          settingsKts.exists() -> settingsKts
          settingsGroovy.exists() -> settingsGroovy
          else -> null
        }

    if (settingsFile != null) {
      val settingsContent = settingsFile.readText()
      if (!settingsContent.contains("mavenLocal")) {
        val pluginMgmt =
            """
            pluginManagement {
                repositories {
                    mavenLocal()
                    gradlePluginPortal()
                }
            }

            """
                .trimIndent()
        settingsFile.writeText(pluginMgmt + settingsContent)
        TerminalUI.fileCreated("Added mavenLocal() to ${settingsFile.name}")
      }
    }

    // Detect Paper version from dependencies, fall back to latest supported
    val match = PAPER_VERSION_PATTERN.find(buildContent)
    val detectedVersion = match?.groupValues?.get(1) ?: PaperVersionResolver().resolveLatest()

    // Create paperplane.yml
    val configFile = File(projectDir, "paperplane.yml")
    if (!configFile.exists()) {
      configFile.writeText(
          """
                server:
                  version: "$detectedVersion"
                  jvm-args:
                    - "-Xmx2G"
            """
              .trimIndent() + "\n"
      )
      TerminalUI.fileCreated("paperplane.yml")
    } else {
      TerminalUI.status("paperplane.yml already exists")
    }

    // Create .paperplane dir and gitignore it
    File(projectDir, ".paperplane").mkdirs()
    val gitignore = File(projectDir, ".gitignore")
    if (gitignore.exists()) {
      val content = gitignore.readText()
      if (!content.contains(".paperplane")) {
        gitignore.appendText("\n# PaperPlane\n.paperplane/\n")
        TerminalUI.fileCreated("Added .paperplane/ to .gitignore")
      }
    }

    TerminalUI.endBlock()

    TerminalUI.beginBlock()
    TerminalUI.success("PaperPlane setup complete")
    TerminalUI.status("Run 'ppl dev' to start developing!")
    TerminalUI.endBlock()
  }
}

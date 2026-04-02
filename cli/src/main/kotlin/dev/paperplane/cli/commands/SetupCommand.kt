package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import dev.paperplane.cli.ui.TerminalUI
import java.io.File

class SetupCommand : CliktCommand(name = "setup") {
    private val projectDir = File(System.getProperty("user.dir"))

    override fun run() {
        val version = javaClass.`package`?.implementationVersion ?: "0.1.0"
        TerminalUI.header(version)

        // Detect build file
        val buildFileKts = File(projectDir, "build.gradle.kts")
        val buildFileGroovy = File(projectDir, "build.gradle")

        val buildFile = when {
            buildFileKts.exists() -> buildFileKts
            buildFileGroovy.exists() -> buildFileGroovy
            else -> {
                TerminalUI.error("No build.gradle or build.gradle.kts found in current directory")
                return
            }
        }

        val isKts = buildFile.name.endsWith(".kts")
        TerminalUI.status("Found ${buildFile.name}")

        // Add plugin to build file if not already present
        val buildContent = buildFile.readText()
        if (buildContent.contains("dev.paperplane")) {
            TerminalUI.status("PaperPlane plugin already applied")
        } else {
            val pluginLine = if (isKts) {
                """    id("dev.paperplane") version "$version""""
            } else {
                """    id 'dev.paperplane' version '$version'"""
            }

            val updated = if (buildContent.contains("plugins {")) {
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
        val settingsFile = when {
            settingsKts.exists() -> settingsKts
            settingsGroovy.exists() -> settingsGroovy
            else -> null
        }

        if (settingsFile != null) {
            val settingsContent = settingsFile.readText()
            if (!settingsContent.contains("mavenLocal")) {
                val isSettingsKts = settingsFile.name.endsWith(".kts")
                val pluginMgmt = if (isSettingsKts) {
                    """
                    pluginManagement {
                        repositories {
                            mavenLocal()
                            gradlePluginPortal()
                        }
                    }

                    """.trimIndent()
                } else {
                    """
                    pluginManagement {
                        repositories {
                            mavenLocal()
                            gradlePluginPortal()
                        }
                    }

                    """.trimIndent()
                }
                settingsFile.writeText(pluginMgmt + settingsContent)
                TerminalUI.fileCreated("Added mavenLocal() to ${settingsFile.name}")
            }
        }

        // Detect Paper version from dependencies
        var detectedVersion = "1.21.4"
        val paperPattern = Regex("""paper-api[:'"]([^'"]+)-R""")
        val match = paperPattern.find(buildContent)
        if (match != null) {
            detectedVersion = match.groupValues[1]
        }

        // Create paperplane.yml
        val configFile = File(projectDir, "paperplane.yml")
        if (!configFile.exists()) {
            configFile.writeText("""
                server:
                  version: "$detectedVersion"
                  jvm-args:
                    - "-Xmx2G"

                dev:
                  overlay: true
            """.trimIndent() + "\n")
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

        TerminalUI.blank()
        TerminalUI.success("PaperPlane setup complete")
        TerminalUI.blank()
        TerminalUI.status("Run 'ppl dev' to start developing!")
    }
}

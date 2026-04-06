package dev.paperplane.gradle

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PaperPlanePluginTest {

  @TempDir lateinit var projectDir: File

  private val baseBuildScript =
      """
      plugins {
          java
          id("dev.paperplane")
      }
      group = "com.example"
      version = "1.0.0"
      repositories {
          mavenCentral()
          maven("https://repo.papermc.io/repository/maven-public/")
      }
      dependencies {
          compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
      }
      paperplane {
          mainClass.set("com.example.TestPlugin")
          pluginName.set("TestPlugin")
      }
      """
          .trimIndent()

  private fun setupProject(buildScript: String) {
    File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "test-plugin"""")
    File(projectDir, "build.gradle.kts").writeText(buildScript)
  }

  private fun runTask(vararg args: String) =
      GradleRunner.create()
          .withProjectDir(projectDir)
          .withPluginClasspath()
          .withArguments(*args)
          .build()

  @Test
  fun `plugin applies successfully`() {
    setupProject(baseBuildScript)
    val result = runTask("tasks", "--group=paperplane")
    assertEquals(TaskOutcome.SUCCESS, result.task(":tasks")?.outcome)
    assertTrue(result.output.contains("ppGeneratePluginYml"))
    assertTrue(result.output.contains("ppMetadata"))
  }

  @Test
  fun `ppGeneratePluginYml task runs successfully`() {
    setupProject(baseBuildScript)
    val result = runTask("ppGeneratePluginYml")
    assertEquals(TaskOutcome.SUCCESS, result.task(":ppGeneratePluginYml")?.outcome)
    val pluginYml = File(projectDir, "build/generated/paperplane/plugin.yml")
    assertTrue(pluginYml.exists(), "plugin.yml should be generated")
  }

  @Test
  fun `processResources includes generated plugin yml`() {
    setupProject(baseBuildScript)
    // Create minimal source so processResources runs fully
    val srcDir = File(projectDir, "src/main/java/com/example")
    srcDir.mkdirs()
    File(srcDir, "TestPlugin.java").writeText("package com.example;\npublic class TestPlugin {}\n")

    val result = runTask("processResources")
    assertEquals(TaskOutcome.SUCCESS, result.task(":processResources")?.outcome)
    val pluginYml = File(projectDir, "build/resources/main/plugin.yml")
    assertTrue(pluginYml.exists(), "plugin.yml should be in processResources output")
    assertTrue(pluginYml.readText().contains("name: TestPlugin"))
  }

  @Test
  fun `skips generation when manual plugin yml exists`() {
    setupProject(baseBuildScript)
    val resourcesDir = File(projectDir, "src/main/resources")
    resourcesDir.mkdirs()
    File(resourcesDir, "plugin.yml").writeText("name: ManualPlugin\n")

    val result = runTask("ppGeneratePluginYml")
    assertTrue(result.output.contains("Manual plugin.yml found"))
  }
}

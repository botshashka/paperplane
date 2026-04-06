package dev.paperplane.gradle

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MetadataTaskTest {

  @TempDir lateinit var projectDir: File

  private fun setupProject(buildScript: String, createSource: Boolean = true) {
    File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "test-plugin"""")
    File(projectDir, "build.gradle.kts").writeText(buildScript)
    if (createSource) {
      val srcDir = File(projectDir, "src/main/java/com/example")
      srcDir.mkdirs()
      File(srcDir, "TestPlugin.java")
          .writeText(
              """
              package com.example;
              public class TestPlugin {}
              """
                  .trimIndent()
          )
    }
  }

  private fun runTask(vararg args: String) =
      GradleRunner.create()
          .withProjectDir(projectDir)
          .withPluginClasspath()
          .withArguments(*args)
          .build()

  private fun readMetadata(): JsonObject {
    val json = File(projectDir, "build/paperplane/metadata.json").readText()
    return JsonParser.parseString(json).asJsonObject
  }

  private val baseBuildScript =
      """
      plugins {
          java
          id("dev.paperplane")
      }
      group = "com.example"
      version = "2.5.0"
      repositories {
          mavenCentral()
          maven("https://repo.papermc.io/repository/maven-public/")
      }
      dependencies {
          compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
      }
      paperplane {
          mainClass.set("com.example.TestPlugin")
          pluginName.set("MyPlugin")
      }
      """
          .trimIndent()

  @Test
  fun `metadata contains expected keys`() {
    setupProject(baseBuildScript)
    val result = runTask("ppMetadata")
    assertEquals(TaskOutcome.SUCCESS, result.task(":ppMetadata")?.outcome)

    val metadata = readMetadata()
    assertTrue(metadata.has("jarPath"), "metadata should contain jarPath")
    assertTrue(metadata.has("mainClass"), "metadata should contain mainClass")
    assertTrue(metadata.has("pluginName"), "metadata should contain pluginName")
    assertTrue(metadata.has("version"), "metadata should contain version")
    assertTrue(metadata.has("paperApiVersion"), "metadata should contain paperApiVersion")
  }

  @Test
  fun `metadata includes correct project version`() {
    setupProject(baseBuildScript)
    runTask("ppMetadata")
    val metadata = readMetadata()

    assertEquals("2.5.0", metadata.get("version").asString)
  }

  @Test
  fun `metadata includes correct field values`() {
    setupProject(baseBuildScript)
    runTask("ppMetadata")
    val metadata = readMetadata()

    assertEquals("com.example.TestPlugin", metadata.get("mainClass").asString)
    assertEquals("MyPlugin", metadata.get("pluginName").asString)
    assertEquals("1.21", metadata.get("paperApiVersion").asString)
  }

  @Test
  fun `pluginName defaults to project name when not explicitly set`() {
    setupProject(
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
        paperplane {
            mainClass.set("com.example.TestPlugin")
        }
        """
            .trimIndent()
    )
    runTask("ppMetadata")
    val metadata = readMetadata()

    assertEquals("test-plugin", metadata.get("pluginName").asString)
  }
}

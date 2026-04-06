package dev.paperplane.gradle

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PluginYmlGenerateTaskTest {

  @TempDir lateinit var projectDir: File

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

  private fun runTaskAndFail(vararg args: String) =
      GradleRunner.create()
          .withProjectDir(projectDir)
          .withPluginClasspath()
          .withArguments(*args)
          .buildAndFail()

  private fun generatedPluginYml(): String =
      File(projectDir, "build/generated/paperplane/plugin.yml").readText()

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

  @Test
  fun `generates basic plugin yml fields`() {
    setupProject(baseBuildScript)
    runTask("ppGeneratePluginYml")
    val yml = generatedPluginYml()

    assertTrue(yml.contains("name: TestPlugin"))
    assertTrue(yml.contains("main: com.example.TestPlugin"))
    assertTrue(yml.contains("version: 1.0.0"))
    assertTrue(yml.contains("api-version: 1.21"))
  }

  @Test
  fun `authors list renders as flow sequence`() {
    setupProject(
        baseBuildScript +
            "\n" +
            """
            paperplane {
                authors.set(listOf("Alice", "Bob"))
            }
            """
                .trimIndent()
    )
    runTask("ppGeneratePluginYml")
    val yml = generatedPluginYml()

    assertTrue(yml.contains("authors: [Alice, Bob]"))
  }

  @Test
  fun `commands block renders correctly`() {
    setupProject(
        baseBuildScript +
            "\n" +
            """
            paperplane {
                commands {
                    create("hello") {
                        description.set("Says hello")
                        usage.set("/hello <player>")
                        aliases.set(listOf("hi", "hey"))
                        permission.set("test.hello")
                    }
                }
            }
            """
                .trimIndent()
    )
    runTask("ppGeneratePluginYml")
    val yml = generatedPluginYml()

    assertTrue(yml.contains("commands:"), "should contain commands section")
    assertTrue(yml.contains("  hello:"), "should contain command name")
    assertTrue(yml.contains("    description: Says hello"), "should contain command description")
    assertTrue(yml.contains("    usage: /hello <player>"), "should contain command usage")
    assertTrue(yml.contains("    aliases: [hi, hey]"), "should contain command aliases")
    assertTrue(yml.contains("    permission: test.hello"), "should contain command permission")
  }

  @Test
  fun `permissions block renders correctly`() {
    setupProject(
        baseBuildScript +
            "\n" +
            """
            paperplane {
                permissions {
                    create("test.admin") {
                        default.set("op")
                        description.set("Admin permission")
                    }
                }
            }
            """
                .trimIndent()
    )
    runTask("ppGeneratePluginYml")
    val yml = generatedPluginYml()

    assertTrue(yml.contains("permissions:"), "should contain permissions section")
    assertTrue(yml.contains("  test.admin:"), "should contain permission name")
    assertTrue(yml.contains("    default: op"), "should contain permission default")
    assertTrue(
        yml.contains("    description: Admin permission"),
        "should contain permission description",
    )
  }

  @Test
  fun `depend and softDepend render as flow sequences`() {
    setupProject(
        baseBuildScript +
            "\n" +
            """
            paperplane {
                depend.set(listOf("Vault", "WorldGuard"))
                softDepend.set(listOf("PlaceholderAPI"))
            }
            """
                .trimIndent()
    )
    runTask("ppGeneratePluginYml")
    val yml = generatedPluginYml()

    assertTrue(yml.contains("depend: [Vault, WorldGuard]"))
    assertTrue(yml.contains("softdepend: [PlaceholderAPI]"))
  }

  @Test
  fun `missing mainClass causes build failure`() {
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
            pluginName.set("TestPlugin")
        }
        """
            .trimIndent()
    )
    val result = runTaskAndFail("ppGeneratePluginYml")
    assertEquals(TaskOutcome.FAILED, result.task(":ppGeneratePluginYml")?.outcome)
    assertTrue(result.output.contains("mainClass must be set"))
  }

  @Test
  fun `empty authors list is omitted from output`() {
    setupProject(baseBuildScript)
    runTask("ppGeneratePluginYml")
    val yml = generatedPluginYml()

    assertFalse(yml.contains("authors:"), "authors field should not be present when list is empty")
  }

  @Test
  fun `website field included when set`() {
    setupProject(
        baseBuildScript +
            "\n" +
            """
            paperplane {
                website.set("https://example.com")
            }
            """
                .trimIndent()
    )
    runTask("ppGeneratePluginYml")
    val yml = generatedPluginYml()

    assertTrue(yml.contains("website: https://example.com"))
  }

  @Test
  fun `permissions with children renders correctly`() {
    setupProject(
        baseBuildScript +
            "\n" +
            """
            paperplane {
                permissions {
                    create("test.all") {
                        default.set("op")
                        description.set("All test permissions")
                        children.set(mapOf("test.use" to true, "test.manage" to false))
                    }
                }
            }
            """
                .trimIndent()
    )
    runTask("ppGeneratePluginYml")
    val yml = generatedPluginYml()

    assertTrue(yml.contains("permissions:"), "should contain permissions section")
    assertTrue(yml.contains("  test.all:"), "should contain permission name")
    assertTrue(yml.contains("    children:"), "should contain children section")
    assertTrue(yml.contains("      test.use: true"), "should contain child permission test.use")
    assertTrue(
        yml.contains("      test.manage: false"),
        "should contain child permission test.manage",
    )
  }

  @Test
  fun `api-version auto-detected from Paper dependency`() {
    setupProject(baseBuildScript)
    runTask("ppGeneratePluginYml")
    val yml = generatedPluginYml()

    assertTrue(yml.contains("api-version: 1.21"))
  }
}

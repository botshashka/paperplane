package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.ui.TerminalUI
import java.io.File

class InitCommand : CliktCommand(name = "init") {
  companion object {
    private const val WRAPPER_TIMEOUT_SECONDS = 60L
  }

  private val name by argument(help = "Project directory name")
  private val pluginName by option("--name", "-n", help = "Plugin name").default("")
  private val packageName by option("--package", "-p", help = "Java package").default("")
  private val paperVersion by option("--paper", help = "Paper MC version").default("1.21.10")
  private val author by option("--author", "-a", help = "Plugin author").default("")
  private val kotlin by
      option("--kotlin", "-k", help = "Use Kotlin instead of Java").default("false")

  override fun run() {
    val projectDir = File(name)
    if (projectDir.exists()) {
      TerminalUI.error("Directory '$name' already exists")
      return
    }

    // Use sensible defaults — no prompts unless explicitly empty
    val defaultPluginName =
        name.split("-", "_").joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
    val defaultPackage = "com.example.${name.lowercase().replace("-", "")}"
    val defaultAuthor = System.getProperty("user.name") ?: "author"

    val resolvedPluginName = pluginName.ifEmpty { defaultPluginName }
    val resolvedPackage = packageName.ifEmpty { defaultPackage }
    val resolvedAuthor = author.ifEmpty { defaultAuthor }
    val useKotlin = kotlin == "true"

    TerminalUI.header(version())

    TerminalUI.beginBlock()
    TerminalUI.status("Creating $name/...")
    TerminalUI.blank()

    val packagePath = resolvedPackage.replace(".", "/")

    // Create directories
    val srcMain = if (useKotlin) "src/main/kotlin/$packagePath" else "src/main/java/$packagePath"
    val srcTest = if (useKotlin) "src/test/kotlin/$packagePath" else "src/test/java/$packagePath"
    val srcResources = "src/main/resources"

    File(projectDir, srcMain).mkdirs()
    File(projectDir, srcTest).mkdirs()
    File(projectDir, srcResources).mkdirs()
    File(projectDir, ".paperplane").mkdirs()

    // Write files from templates
    writeTemplate(
        projectDir,
        "build.gradle.kts",
        buildGradle(resolvedPluginName, resolvedPackage, paperVersion),
    )
    TerminalUI.fileCreated("build.gradle.kts")

    writeTemplate(projectDir, "settings.gradle.kts", settingsGradle(name))
    TerminalUI.fileCreated("settings.gradle.kts")

    if (useKotlin) {
      writeTemplate(
          projectDir,
          "$srcMain/${resolvedPluginName}.kt",
          mainPluginKt(resolvedPackage, resolvedPluginName),
      )
      writeTemplate(
          projectDir,
          "$srcTest/${resolvedPluginName}Test.kt",
          testPluginKt(resolvedPackage, resolvedPluginName),
      )
      TerminalUI.fileCreated("$srcMain/${resolvedPluginName}.kt")
      TerminalUI.fileCreated("$srcTest/${resolvedPluginName}Test.kt")
    } else {
      writeTemplate(
          projectDir,
          "$srcMain/${resolvedPluginName}.java",
          mainPluginJava(resolvedPackage, resolvedPluginName),
      )
      writeTemplate(
          projectDir,
          "$srcTest/${resolvedPluginName}Test.java",
          testPluginJava(resolvedPackage, resolvedPluginName),
      )
      TerminalUI.fileCreated("$srcMain/${resolvedPluginName}.java")
      TerminalUI.fileCreated("$srcTest/${resolvedPluginName}Test.java")
    }

    writeTemplate(projectDir, "$srcResources/config.yml", "# ${resolvedPluginName} configuration\n")
    TerminalUI.fileCreated("$srcResources/config.yml")

    writeTemplate(projectDir, "paperplane.yml", paperplaneYml(paperVersion))
    TerminalUI.fileCreated("paperplane.yml")

    writeTemplate(projectDir, ".gitignore", gitignore())
    TerminalUI.fileCreated(".gitignore")

    TerminalUI.blank()
    // Generate Gradle wrapper
    TerminalUI.status("Generating Gradle wrapper...")
    val wrapperProcess =
        ProcessBuilder("gradle", "wrapper", "--gradle-version", "9.4.1")
            .directory(projectDir)
            .redirectErrorStream(true)
            .start()
    wrapperProcess.waitFor(WRAPPER_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    if (wrapperProcess.exitValue() == 0) {
      TerminalUI.fileCreated("gradlew")
    } else {
      TerminalUI.error("Failed to generate Gradle wrapper — run 'gradle wrapper' manually")
    }
    TerminalUI.endBlock()

    TerminalUI.beginBlock()
    TerminalUI.success("Project created")
    TerminalUI.blank()
    TerminalUI.status("Next steps:")
    TerminalUI.info("cd", name)
    TerminalUI.info("ppl", "dev")
    TerminalUI.endBlock()
  }

  private fun promptOrDefault(label: String, default: String): String {
    print("  $label [$default]: ")
    val input = readlnOrNull()?.trim()
    return if (input.isNullOrEmpty()) default else input
  }

  private fun version(): String = javaClass.`package`?.implementationVersion ?: "0.1.0"

  private fun writeTemplate(projectDir: File, path: String, content: String) {
    val file = File(projectDir, path)
    file.parentFile.mkdirs()
    file.writeText(content)
  }

  // --- Templates ---

  private fun buildGradle(pluginName: String, packageName: String, paperVersion: String) =
      """
        plugins {
            java
            id("dev.paperplane") version "0.1.0"
        }

        group = "$packageName"
        version = "1.0.0"

        java {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        repositories {
            mavenCentral()
            maven("https://repo.papermc.io/repository/maven-public/")
        }

        dependencies {
            compileOnly("io.papermc.paper:paper-api:$paperVersion-R0.1-SNAPSHOT")

            testImplementation("io.papermc.paper:paper-api:$paperVersion-R0.1-SNAPSHOT")
            testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.108.0")
            testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher:5.11.4")
        }

        paperplane {
            mainClass.set("$packageName.$pluginName")
            pluginName.set("$pluginName")
            authors.set(listOf("$pluginName"))
        }

        tasks.withType<Test> {
            useJUnitPlatform()
        }
    """
          .trimIndent() + "\n"

  private fun settingsGradle(projectName: String) =
      """
        pluginManagement {
            repositories {
                mavenLocal()
                gradlePluginPortal()
            }
        }

        rootProject.name = "$projectName"
    """
          .trimIndent() + "\n"

  private fun mainPluginJava(packageName: String, pluginName: String) =
      """
        package $packageName;

        import org.bukkit.plugin.java.JavaPlugin;

        public class $pluginName extends JavaPlugin {
            @Override
            public void onEnable() {
                getLogger().info("$pluginName enabled!");
            }

            @Override
            public void onDisable() {
                getLogger().info("$pluginName disabled!");
            }
        }
    """
          .trimIndent() + "\n"

  private fun mainPluginKt(packageName: String, pluginName: String) =
      """
        package $packageName

        import org.bukkit.plugin.java.JavaPlugin

        class $pluginName : JavaPlugin() {
            override fun onEnable() {
                logger.info("$pluginName enabled!")
            }

            override fun onDisable() {
                logger.info("$pluginName disabled!")
            }
        }
    """
          .trimIndent() + "\n"

  private fun testPluginJava(packageName: String, pluginName: String) =
      """
        package $packageName;

        import org.junit.jupiter.api.AfterEach;
        import org.junit.jupiter.api.BeforeEach;
        import org.junit.jupiter.api.Test;
        import org.mockbukkit.mockbukkit.MockBukkit;
        import org.mockbukkit.mockbukkit.ServerMock;

        import static org.junit.jupiter.api.Assertions.assertNotNull;

        class ${pluginName}Test {
            private ServerMock server;
            private $pluginName plugin;

            @BeforeEach
            void setUp() {
                server = MockBukkit.mock();
                plugin = MockBukkit.load(${pluginName}.class);
            }

            @AfterEach
            void tearDown() {
                MockBukkit.unmock();
            }

            @Test
            void pluginLoads() {
                assertNotNull(plugin);
            }
        }
    """
          .trimIndent() + "\n"

  private fun testPluginKt(packageName: String, pluginName: String) =
      """
        package $packageName

        import org.junit.jupiter.api.AfterEach
        import org.junit.jupiter.api.BeforeEach
        import org.junit.jupiter.api.Test
        import org.mockbukkit.mockbukkit.MockBukkit
        import org.mockbukkit.mockbukkit.ServerMock
        import kotlin.test.assertNotNull

        class ${pluginName}Test {
            private lateinit var server: ServerMock
            private lateinit var plugin: $pluginName

            @BeforeEach
            fun setUp() {
                server = MockBukkit.mock()
                plugin = MockBukkit.load(${pluginName}::class.java)
            }

            @AfterEach
            fun tearDown() {
                MockBukkit.unmock()
            }

            @Test
            fun pluginLoads() {
                assertNotNull(plugin)
            }
        }
    """
          .trimIndent() + "\n"

  private fun paperplaneYml(paperVersion: String) =
      """
        server:
          version: "$paperVersion"
          jvm-args:
            - "-Xmx2G"

        dev:
          mode: hot-reload      # hot-reload | blue-green | restart
          # jbr: auto           # auto | on | off | /path/to/jbr
    """
          .trimIndent() + "\n"

  private fun gitignore() =
      """
      # Build
      build/
      .gradle/

      # IDE
      .idea/
      *.iml
      .vscode/

      # OS
      .DS_Store

      # PaperPlane
      .paperplane/
      """
          .trimIndent() + "\n"
}

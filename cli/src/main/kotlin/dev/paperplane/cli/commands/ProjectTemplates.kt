package dev.paperplane.cli.commands

import dev.paperplane.cli.Versions
import java.io.File

internal object ProjectTemplates {

  fun writeTemplate(projectDir: File, path: String, content: String) {
    val file = File(projectDir, path)
    file.parentFile.mkdirs()
    file.writeText(content)
  }

  fun buildGradle(
      displayName: String,
      className: String,
      packageName: String,
      author: String,
      paperVersion: String,
      isKotlin: Boolean,
  ): String {
    val plugins =
        buildList {
              add("java")
              if (isKotlin) {
                add("kotlin(\"jvm\") version \"${Versions.KOTLIN}\"")
                add("id(\"com.gradleup.shadow\") version \"${Versions.SHADOW}\"")
              }
              add("id(\"dev.paperplane\") version \"${Versions.paperplaneVersion()}\"")
            }
            .joinToString("\n") { "    $it" }

    val deps =
        buildList {
              if (isKotlin) add("    implementation(kotlin(\"stdlib\"))")
              add("    compileOnly(\"io.papermc.paper:paper-api:$paperVersion-R0.1-SNAPSHOT\")")
              add("")
              add(
                  "    testImplementation(\"io.papermc.paper:paper-api:$paperVersion-R0.1-SNAPSHOT\")"
              )
              add(
                  "    testImplementation(\"org.mockbukkit.mockbukkit:${Versions.mockbukkitArtifact(paperVersion)}:${Versions.MOCKBUKKIT}\")"
              )
              add("    testImplementation(\"org.junit.jupiter:junit-jupiter:${Versions.JUNIT}\")")
              add(
                  "    testRuntimeOnly(\"org.junit.platform:junit-platform-launcher:${Versions.JUNIT}\")"
              )
              if (isKotlin) add("    testImplementation(\"org.jetbrains.kotlin:kotlin-test\")")
            }
            .joinToString("\n")

    return """
        plugins {
        $plugins
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
        $deps
        }

        paperplane {
            mainClass.set("$packageName.$className")
            pluginName.set("$displayName")
            authors.set(listOf("$author"))
        }

        tasks.withType<Test> {
            useJUnitPlatform()
        }
    """
        .trimIndent() + "\n"
  }

  fun settingsGradle(projectName: String) =
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

  fun mainPluginJava(packageName: String, className: String, displayName: String) =
      """
        package $packageName;

        import org.bukkit.plugin.java.JavaPlugin;

        public class $className extends JavaPlugin {
            @Override
            public void onEnable() {
                getLogger().info("$displayName enabled!");
            }

            @Override
            public void onDisable() {
                getLogger().info("$displayName disabled!");
            }
        }
    """
          .trimIndent() + "\n"

  fun mainPluginKt(packageName: String, className: String, displayName: String) =
      """
        package $packageName

        import org.bukkit.plugin.java.JavaPlugin

        class $className : JavaPlugin() {
            override fun onEnable() {
                logger.info("$displayName enabled!")
            }

            override fun onDisable() {
                logger.info("$displayName disabled!")
            }
        }
    """
          .trimIndent() + "\n"

  fun testPluginJava(packageName: String, className: String) =
      """
        package $packageName;

        import org.junit.jupiter.api.AfterEach;
        import org.junit.jupiter.api.BeforeEach;
        import org.junit.jupiter.api.Test;
        import org.mockbukkit.mockbukkit.MockBukkit;
        import org.mockbukkit.mockbukkit.ServerMock;

        import static org.junit.jupiter.api.Assertions.assertNotNull;

        class ${className}Test {
            private ServerMock server;
            private $className plugin;

            @BeforeEach
            void setUp() {
                server = MockBukkit.mock();
                plugin = MockBukkit.load(${className}.class);
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

  fun testPluginKt(packageName: String, className: String) =
      """
        package $packageName

        import org.junit.jupiter.api.AfterEach
        import org.junit.jupiter.api.BeforeEach
        import org.junit.jupiter.api.Test
        import org.mockbukkit.mockbukkit.MockBukkit
        import org.mockbukkit.mockbukkit.ServerMock
        import kotlin.test.assertNotNull

        class ${className}Test {
            private lateinit var server: ServerMock
            private lateinit var plugin: $className

            @BeforeEach
            fun setUp() {
                server = MockBukkit.mock()
                plugin = MockBukkit.load(${className}::class.java)
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

  fun paperplaneYml(paperVersion: String, devMode: String, jbr: String) =
      """
        server:
          version: "$paperVersion"
          jvm-args:
            - "-Xmx2G"

        dev:
          mode: $devMode      # hot-reload | blue-green | restart
          jbr: "$jbr"             # auto | on | off | /path/to/jbr
    """
          .trimIndent() + "\n"

  fun gitignore() =
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

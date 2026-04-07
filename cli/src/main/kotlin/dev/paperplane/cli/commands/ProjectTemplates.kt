package dev.paperplane.cli.commands

import dev.paperplane.cli.Versions
import java.io.File

internal object ProjectTemplates {

  /**
   * `pluginManagement` block that lets a generated project resolve the `dev.paperplane` Gradle
   * plugin from `~/.m2`. Shared between [settingsGradle] (new projects) and [InitCommand] (existing
   * projects) so the two sources of truth stay in sync.
   */
  const val PLUGIN_MANAGEMENT_BLOCK =
      """pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

"""

  /**
   * Returns true if the dev.paperplane gradle plugin is available in the local Maven repo for the
   * current paperplane version. When true, generated projects need `mavenLocal()` in their
   * pluginManagement repositories to resolve it. Released paperplane installs (where the plugin
   * lives on the Gradle Plugin Portal) won't have it locally → no mavenLocal needed.
   */
  fun gradlePluginInMavenLocal(): Boolean {
    val home = System.getProperty("user.home") ?: return false
    val version = Versions.paperplaneVersion()
    val artifactDir =
        File("$home/.m2/repository/dev/paperplane/dev.paperplane.gradle.plugin/$version")
    return artifactDir.isDirectory
  }

  fun writeTemplate(projectDir: File, path: String, content: String) {
    val file = File(projectDir, path)
    file.parentFile.mkdirs()
    file.writeText(content)
  }

  fun buildGradle(
      className: String,
      packageName: String,
      author: String,
      paperVersion: String,
      isKotlin: Boolean,
  ): String {
    val plugins = buildPluginsBlock(isKotlin)
    val kotlinBlock = buildKotlinToolchainBlock(isKotlin)
    val spotlessBlock = buildSpotlessBlock(isKotlin)
    val deps = buildDependenciesBlock(paperVersion, isKotlin)
    return renderBuildGradleTemplate(
        className,
        packageName,
        author,
        plugins,
        kotlinBlock,
        spotlessBlock,
        deps,
    )
  }

  private fun buildPluginsBlock(isKotlin: Boolean): String =
      buildList {
            add("java")
            if (isKotlin) {
              add("kotlin(\"jvm\") version \"${Versions.KOTLIN}\"")
              add("id(\"com.gradleup.shadow\") version \"${Versions.SHADOW}\"")
              add("id(\"com.diffplug.spotless\") version \"${Versions.SPOTLESS}\"")
            }
            add("id(\"dev.paperplane\") version \"${Versions.paperplaneVersion()}\"")
          }
          .joinToString("\n") { "    $it" }

  private fun buildKotlinToolchainBlock(isKotlin: Boolean): String =
      if (isKotlin) "\n\n        kotlin {\n            jvmToolchain(21)\n        }" else ""

  private fun buildSpotlessBlock(isKotlin: Boolean): String =
      if (!isKotlin) ""
      else
          buildString {
            append("\n\n        spotless {")
            append("\n            kotlin {")
            append("\n                target(\"src/**/*.kt\")")
            append("\n                ktfmt()")
            append("\n            }")
            append("\n            kotlinGradle {")
            append("\n                target(\"*.gradle.kts\")")
            append("\n                ktfmt()")
            append("\n            }")
            append("\n        }")
          }

  private fun buildDependenciesBlock(paperVersion: String, isKotlin: Boolean): String {
    val mockbukkitCoord =
        "org.mockbukkit.mockbukkit:${Versions.mockbukkitArtifact(paperVersion)}:${Versions.MOCKBUKKIT}"
    return buildList {
          if (isKotlin) add("    implementation(kotlin(\"stdlib\"))")
          add("    compileOnly(\"io.papermc.paper:paper-api:$paperVersion-R0.1-SNAPSHOT\")")
          add("")
          add("    testImplementation(\"io.papermc.paper:paper-api:$paperVersion-R0.1-SNAPSHOT\")")
          add("    testImplementation(\"$mockbukkitCoord\")")
          add("    testImplementation(\"org.junit.jupiter:junit-jupiter:${Versions.JUNIT}\")")
          add(
              "    testRuntimeOnly(\"org.junit.platform:junit-platform-launcher:${Versions.JUNIT}\")"
          )
          if (isKotlin) add("    testImplementation(\"org.jetbrains.kotlin:kotlin-test\")")
        }
        .joinToString("\n")
  }

  private fun renderBuildGradleTemplate(
      className: String,
      packageName: String,
      author: String,
      plugins: String,
      kotlinBlock: String,
      spotlessBlock: String,
      deps: String,
  ): String {
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
        }$kotlinBlock$spotlessBlock

        repositories {
            mavenCentral()
            maven("https://repo.papermc.io/repository/maven-public/")
        }

        dependencies {
        $deps
        }

        paperplane {
            mainClass.set("$packageName.$className")
            pluginName.set("$className")
            authors.set(listOf("$author"))
        }

        tasks.withType<Test> {
            useJUnitPlatform()
        }
    """
        .trimIndent() + "\n"
  }

  fun settingsGradle(projectName: String): String {
    val pluginMgmt = if (gradlePluginInMavenLocal()) PLUGIN_MANAGEMENT_BLOCK else ""
    return pluginMgmt + "rootProject.name = \"$projectName\"\n"
  }

  fun mainPluginJava(packageName: String, className: String, displayName: String) =
      """
        package $packageName;

        import org.bukkit.plugin.java.JavaPlugin;

        public class $className extends JavaPlugin {
            @Override
            public void onEnable() {
                saveDefaultConfig();
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
                saveDefaultConfig()
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

      # OS
      .DS_Store

      # PaperPlane
      .paperplane/
      """
          .trimIndent() + "\n"

  fun readme(displayName: String) =
      """
        # $displayName

        A Paper plugin scaffolded with [PaperPlane](https://github.com/botshashka/paperplane).

        ## Develop

        ```bash
        ppl dev
        ```

        ## Test

        ```bash
        ppl test
        ```

        ## Format

        ```bash
        ppl format
        ```

        ## Build

        ```bash
        ./gradlew build
        ```

        Built jar: `build/libs/`
    """
          .trimIndent() + "\n"

  fun vscodeExtensions() =
      """
      {
        "recommendations": [
          "vscjava.vscode-java-pack",
          "fwcd.kotlin"
        ]
      }
      """
          .trimIndent() + "\n"

  fun vscodeSettings() =
      """
      {
        "editor.formatOnSave": true,
        "java.format.settings.profile": "GoogleStyle"
      }
      """
          .trimIndent() + "\n"
}

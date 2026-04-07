package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.Versions
import dev.paperplane.cli.server.PaperVersionResolver
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.util.JavaRuntimeUtil
import dev.paperplane.cli.util.Platform
import java.io.File

class CreateCommand : CliktCommand(name = "create") {
  companion object {
    private const val WRAPPER_TIMEOUT_SECONDS = 60L

    private val DEV_MODE_OPTIONS = listOf("Hot reload", "Blue-green", "Restart")
    private val DEV_MODE_DESCRIPTIONS =
        listOf(
            "fastest iteration, reloads plugin in-place",
            "zero-downtime via Velocity proxy",
            "stop, rebuild, restart",
        )
    private val DEV_MODE_VALUES = listOf("hot-reload", "blue-green", "restart")

    private val CREATING_MESSAGES =
        listOf(
            "Folding your plugin...",
            "Smoothing things out...",
            "Shaping things up...",
            "Putting it together...",
        )

    private val SUCCESS_MESSAGES =
        listOf(
            "Your plugin is ready!",
            "All set!",
            "Good to go!",
            "All yours!",
        )
  }

  private data class ProjectConfig(
      val projectDir: File,
      val projectName: String,
      val displayName: String,
      val className: String,
      val packageName: String,
      val author: String,
      val paperVersion: String,
      val useKotlin: Boolean,
      val devMode: String,
      val jbr: String,
  )

  private val name by argument(help = "Project directory name").optional()
  private val pluginName by option("--name", "-n", help = "Plugin display name").default("")
  private val paperVersion by option("--paper", help = "Paper MC version").default("")
  private val author by option("--author", "-a", help = "Plugin author").default("")
  private val useKotlin by option("--kotlin", "-k", help = "Use Kotlin instead of Java").flag()

  override fun run() {
    if (name == null) {
      runInteractive()
    } else {
      runNonInteractive()
    }
    TerminalUI.endView()
  }

  private fun deriveSlug(displayName: String): String =
      displayName.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

  private fun deriveClassName(slug: String): String =
      slug.split("-").filter { it.isNotEmpty() }.joinToString("") {
        it.replaceFirstChar { c -> c.uppercase() }
      }

  private fun deriveDisplayName(slug: String): String =
      slug.split("-").filter { it.isNotEmpty() }.joinToString(" ") {
        it.replaceFirstChar { c -> c.uppercase() }
      }

  private fun derivePackage(author: String, slug: String): String =
      "me.${author.lowercase().replace(Regex("[^a-z0-9]"), "")}.${slug.replace("-", "")}"

  private fun isValidPackage(pkg: String): Boolean {
    if (pkg.isEmpty()) return false
    val segments = pkg.split(".")
    return segments.all { seg ->
      seg.isNotEmpty() &&
          seg.first().isLetter() &&
          seg.all { it.isLetterOrDigit() || it == '_' } &&
          seg.first().isLowerCase()
    }
  }

  private fun deriveAuthor(): String = System.getProperty("user.name") ?: "author"

  private fun runInteractive() {
    TerminalUI.header(Versions.paperplaneVersion())
    TerminalUI.subtitle("Let's make a new Paper plugin")

    var displayName: String
    var slug: String
    var projectDir: File
    while (true) {
      displayName = TerminalUI.prompt("Plugin name", "My Plugin")
      slug = deriveSlug(displayName)
      if (slug.isEmpty()) {
        TerminalUI.beginBlock()
        TerminalUI.error("Could not derive a project name from '$displayName'")
        TerminalUI.endBlock()
        continue
      }
      projectDir = File(slug)
      if (projectDir.exists()) {
        TerminalUI.beginBlock()
        TerminalUI.error("Directory '$slug' already exists")
        TerminalUI.endBlock()
        continue
      }
      break
    }

    val resolvedAuthor = TerminalUI.prompt("Author", deriveAuthor())
    val className = deriveClassName(slug)
    var resolvedPackage: String
    while (true) {
      resolvedPackage = TerminalUI.prompt("Package", derivePackage(resolvedAuthor, slug))
      if (isValidPackage(resolvedPackage)) break
      TerminalUI.beginBlock()
      TerminalUI.error("Invalid package name (use lowercase segments like com.example.myplugin)")
      TerminalUI.endBlock()
    }

    val versions =
        TerminalUI.spin("Resolving Paper versions...") { PaperVersionResolver().resolveRecent(5) }
    val versionLabels = versions.mapIndexed { i, v ->
      if (i == versions.lastIndex) "$v (latest)" else v
    }
    val versionIndex =
        TerminalUI.select("Paper version", versionLabels, default = versions.lastIndex)
    val resolvedPaperVersion = versions[versionIndex]

    val langIndex = TerminalUI.select("Language", listOf("Java", "Kotlin"))
    val isKotlin = langIndex == 1

    val modeIndex =
        TerminalUI.select(
            "Dev mode",
            DEV_MODE_OPTIONS,
            DEV_MODE_DESCRIPTIONS,
            note = "change anytime in paperplane.yml",
        )
    val devMode = DEV_MODE_VALUES[modeIndex]

    val jbr =
        if (devMode == "hot-reload") {
          resolveJbrSetting()
        } else {
          "auto"
        }

    val eulaChoice =
        TerminalUI.select(
            "Do you accept the Minecraft EULA?",
            listOf("Yes", "No"),
            note = "https://aka.ms/MinecraftEULA",
        )
    if (eulaChoice != 0) {
      TerminalUI.beginBlock()
      TerminalUI.error("You must accept the Minecraft EULA to run a server")
      TerminalUI.endBlock()
      return
    }

    createProject(
        ProjectConfig(
            projectDir = projectDir,
            projectName = slug,
            displayName = displayName,
            className = className,
            packageName = resolvedPackage,
            author = resolvedAuthor,
            paperVersion = resolvedPaperVersion,
            useKotlin = isKotlin,
            devMode = devMode,
            jbr = jbr,
        ))
  }

  private fun runNonInteractive() {
    val slug = name!!
    val projectDir = File(slug)

    TerminalUI.header(Versions.paperplaneVersion())

    if (projectDir.exists()) {
      TerminalUI.beginBlock()
      TerminalUI.error("Directory '$slug' already exists")
      TerminalUI.endBlock()
      return
    }

    val displayName = pluginName.ifEmpty { deriveDisplayName(slug) }
    val className = deriveClassName(slug)
    val resolvedAuthor = author.ifEmpty { deriveAuthor() }
    val resolvedPackage = derivePackage(resolvedAuthor, slug)

    val resolvedPaperVersion =
        if (paperVersion.isEmpty()) {
          TerminalUI.spin("Resolving latest Paper version...") {
            PaperVersionResolver().resolveLatest()
          }
        } else paperVersion

    createProject(
        ProjectConfig(
            projectDir = projectDir,
            projectName = slug,
            displayName = displayName,
            className = className,
            packageName = resolvedPackage,
            author = resolvedAuthor,
            paperVersion = resolvedPaperVersion,
            useKotlin = useKotlin,
            devMode = "hot-reload",
            jbr = "auto",
        ))
  }

  /** Detects JBR availability and prompts if needed. Returns the jbr config value. */
  private fun resolveJbrSetting(): String {
    // Check if system java is already JBR
    if (JavaRuntimeUtil.checkIsJbr("java")) {
      TerminalUI.beginBlock()
      TerminalUI.success("JetBrains Runtime detected")
      TerminalUI.endBlock()
      return "auto"
    }

    // Check if JBR is cached
    val jbrCache = File(Platform.paperplaneHome, "jbr")
    if (jbrCache.exists() && jbrCache.listFiles()?.isNotEmpty() == true) {
      TerminalUI.beginBlock()
      TerminalUI.success("JetBrains Runtime found")
      TerminalUI.endBlock()
      return "auto"
    }

    // Prompt user
    val choice =
        TerminalUI.select(
            "JetBrains Runtime",
            listOf("Yes, use JBR", "No thanks"),
            note = "enables more reliable hot swapping (~200 MB download)",
        )
    return if (choice == 0) "on" else "off"
  }

  private fun createProject(config: ProjectConfig) {
    val wrapperOk =
        TerminalUI.spin(CREATING_MESSAGES.random()) { scaffoldFiles(config) }

    TerminalUI.beginBlock()
    TerminalUI.success("${SUCCESS_MESSAGES.random()} — ${config.projectName}/")
    if (!wrapperOk) {
      TerminalUI.error("Gradle wrapper failed — run 'gradle wrapper' manually")
    }
    TerminalUI.endBlock()

    TerminalUI.beginBlock()
    TerminalUI.info("cd", "${config.projectName}  ${TerminalUI.dim("switch to your project folder")}")
    TerminalUI.info("ppl", "dev  ${TerminalUI.dim("launch the dev server")}")
    TerminalUI.endBlock()
  }

  /** Writes all template files and runs `gradle wrapper`. Returns true if wrapper succeeded. */
  private fun scaffoldFiles(c: ProjectConfig): Boolean {
    val packagePath = c.packageName.replace(".", "/")
    val srcMain =
        if (c.useKotlin) "src/main/kotlin/$packagePath" else "src/main/java/$packagePath"
    val srcTest =
        if (c.useKotlin) "src/test/kotlin/$packagePath" else "src/test/java/$packagePath"
    val srcResources = "src/main/resources"

    File(c.projectDir, srcMain).mkdirs()
    File(c.projectDir, srcTest).mkdirs()
    File(c.projectDir, srcResources).mkdirs()
    File(c.projectDir, ".paperplane").mkdirs()

    ProjectTemplates.writeTemplate(
        c.projectDir,
        "build.gradle.kts",
        ProjectTemplates.buildGradle(
            c.className, c.packageName, c.author, c.paperVersion, c.useKotlin),
    )
    ProjectTemplates.writeTemplate(
        c.projectDir, "settings.gradle.kts", ProjectTemplates.settingsGradle(c.projectName))

    if (c.useKotlin) {
      ProjectTemplates.writeTemplate(
          c.projectDir,
          "$srcMain/${c.className}.kt",
          ProjectTemplates.mainPluginKt(c.packageName, c.className, c.displayName),
      )
      ProjectTemplates.writeTemplate(
          c.projectDir,
          "$srcTest/${c.className}Test.kt",
          ProjectTemplates.testPluginKt(c.packageName, c.className),
      )
    } else {
      ProjectTemplates.writeTemplate(
          c.projectDir,
          "$srcMain/${c.className}.java",
          ProjectTemplates.mainPluginJava(c.packageName, c.className, c.displayName),
      )
      ProjectTemplates.writeTemplate(
          c.projectDir,
          "$srcTest/${c.className}Test.java",
          ProjectTemplates.testPluginJava(c.packageName, c.className),
      )
    }

    ProjectTemplates.writeTemplate(
        c.projectDir,
        "$srcResources/config.yml",
        "# ${c.displayName} configuration\n",
    )
    ProjectTemplates.writeTemplate(
        c.projectDir, "paperplane.yml", ProjectTemplates.paperplaneYml(c.paperVersion, c.devMode, c.jbr))
    ProjectTemplates.writeTemplate(c.projectDir, ".gitignore", ProjectTemplates.gitignore())
    ProjectTemplates.writeTemplate(c.projectDir, "README.md", ProjectTemplates.readme(c.displayName))
    ProjectTemplates.writeTemplate(
        c.projectDir, ".vscode/extensions.json", ProjectTemplates.vscodeExtensions())
    ProjectTemplates.writeTemplate(
        c.projectDir, ".vscode/settings.json", ProjectTemplates.vscodeSettings())

    val wrapperProcess =
        ProcessBuilder("gradle", "wrapper", "--gradle-version", Versions.GRADLE_WRAPPER)
            .directory(c.projectDir)
            .redirectErrorStream(true)
            .start()
    val finished =
        wrapperProcess.waitFor(WRAPPER_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    return if (!finished) {
      wrapperProcess.destroyForcibly()
      false
    } else {
      wrapperProcess.exitValue() == 0
    }
  }
}

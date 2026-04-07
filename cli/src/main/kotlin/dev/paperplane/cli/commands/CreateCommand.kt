package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.Versions
import dev.paperplane.cli.server.PaperVersionResolver
import dev.paperplane.cli.ui.EXIT_CANCELLED
import dev.paperplane.cli.ui.InteractivePrompts
import dev.paperplane.cli.ui.PromptCancelledException
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.util.JavaRuntimeUtil
import dev.paperplane.cli.util.Platform
import java.io.File

class CreateCommand : CliktCommand(name = "create") {
  companion object {
    private const val WRAPPER_TIMEOUT_SECONDS = 60L
    private const val PAPER_VERSIONS_TO_SHOW = 5

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

  private enum class DevMode(val display: String, val description: String, val value: String) {
    HOT_RELOAD("Hot reload", "fastest iteration, reloads plugin in-place", "hot-reload"),
    BLUE_GREEN("Blue-green", "zero-downtime via Velocity proxy", "blue-green"),
    RESTART("Restart", "stop, rebuild, restart", "restart"),
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
    InteractivePrompts.beginInteractiveView()
    try {
      if (name == null) {
        runInteractive()
      } else {
        runNonInteractive()
      }
    } catch (_: PromptCancelledException) {
      rollbackScaffold()
      TerminalUI.cancelled()
      throw ProgramResult(EXIT_CANCELLED)
    } finally {
      InteractivePrompts.endInteractiveView()
      TerminalUI.endView()
    }
  }

  @Volatile private var scaffoldInProgress: File? = null
  @Volatile private var wrapperProcess: Process? = null

  private fun rollbackScaffold() {
    val dir = scaffoldInProgress ?: return
    scaffoldInProgress = null
    try {
      wrapperProcess?.destroyForcibly()
    } catch (_: Exception) {
      // best-effort
    }
    try {
      if (dir.exists()) dir.deleteRecursively()
    } catch (_: Exception) {
      // best-effort
    }
  }

  private fun deriveSlug(displayName: String): String =
      displayName.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

  private fun slugWords(slug: String): List<String> =
      slug.split("-").filter { it.isNotEmpty() }.map { it.replaceFirstChar { c -> c.uppercase() } }

  private fun deriveClassName(slug: String): String = slugWords(slug).joinToString("")

  private fun deriveDisplayName(slug: String): String = slugWords(slug).joinToString(" ")

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
      displayName = InteractivePrompts.prompt("Plugin name", "My Plugin")
      slug = deriveSlug(displayName)
      if (slug.isEmpty()) {
        TerminalUI.block { error("Could not derive a project name from '$displayName'") }
        continue
      }
      projectDir = File(slug)
      if (projectDir.exists()) {
        TerminalUI.block { error("Directory '$slug' already exists") }
        continue
      }
      break
    }

    val resolvedAuthor = InteractivePrompts.prompt("Author", deriveAuthor())
    val className = deriveClassName(slug)
    var resolvedPackage: String
    while (true) {
      resolvedPackage = InteractivePrompts.prompt("Package", derivePackage(resolvedAuthor, slug))
      if (isValidPackage(resolvedPackage)) break
      TerminalUI.block {
        error("Invalid package name (use lowercase segments like com.example.myplugin)")
      }
    }

    val versions =
        TerminalUI.spin("Resolving Paper versions...") {
          PaperVersionResolver().resolveRecent(PAPER_VERSIONS_TO_SHOW)
        }
    val versionLabels = versions.mapIndexed { i, v ->
      if (i == versions.lastIndex) "$v (latest)" else v
    }
    val versionIndex =
        InteractivePrompts.select("Paper version", versionLabels, default = versions.lastIndex)
    val resolvedPaperVersion = versions[versionIndex]

    val langIndex = InteractivePrompts.select("Language", listOf("Java", "Kotlin"))
    val isKotlin = langIndex == 1

    val modeIndex =
        InteractivePrompts.select(
            "Dev mode",
            DevMode.entries.map { InteractivePrompts.SelectOption(it.display, it.description) },
            note = "change anytime in paperplane.yml",
        )
    val devMode = DevMode.entries[modeIndex]

    val jbr = if (devMode == DevMode.HOT_RELOAD) resolveJbrSetting() else "auto"

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
            devMode = devMode.value,
            jbr = jbr,
        )
    )
  }

  private fun runNonInteractive() {
    val slug = name!!
    val projectDir = File(slug)

    TerminalUI.header(Versions.paperplaneVersion())

    if (projectDir.exists()) {
      TerminalUI.block { error("Directory '$slug' already exists") }
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
            devMode = DevMode.HOT_RELOAD.value,
            jbr = "auto",
        )
    )
  }

  private fun resolveJbrSetting(): String {
    if (JavaRuntimeUtil.checkIsJbr("java")) {
      TerminalUI.block { success("JetBrains Runtime detected") }
      return "auto"
    }

    val jbrCache = File(Platform.paperplaneHome, "jbr")
    if (jbrCache.exists() && jbrCache.listFiles()?.isNotEmpty() == true) {
      TerminalUI.block { success("JetBrains Runtime found") }
      return "auto"
    }

    val choice =
        InteractivePrompts.select(
            "JetBrains Runtime",
            listOf("Yes, use JBR", "No thanks"),
            note = "enables more reliable hot swapping (~200 MB download)",
        )
    return if (choice == 0) "on" else "off"
  }

  private fun createProject(config: ProjectConfig) {
    val wrapperOk = TerminalUI.spin(CREATING_MESSAGES.random()) { scaffoldFiles(config) }

    TerminalUI.block {
      success("${SUCCESS_MESSAGES.random()} — ${config.projectName}/")
      if (!wrapperOk) {
        error("Gradle wrapper failed — run 'gradle wrapper' manually")
      }
    }

    TerminalUI.block {
      info("cd", "${config.projectName}  ${TerminalUI.dim("switch to your project folder")}")
      info("ppl", "dev  ${TerminalUI.dim("launch the dev server")}")
    }
  }

  private fun scaffoldFiles(c: ProjectConfig): Boolean {
    val createdByUs = !c.projectDir.exists()
    scaffoldInProgress = if (createdByUs) c.projectDir else null
    val hook =
        if (createdByUs) {
          Thread(
                  {
                    val dir = scaffoldInProgress ?: return@Thread
                    try {
                      wrapperProcess?.destroyForcibly()
                    } catch (_: Exception) {}
                    try {
                      if (dir.exists()) dir.deleteRecursively()
                    } catch (_: Exception) {}
                  },
                  "create-scaffold-rollback",
              )
              .also { Runtime.getRuntime().addShutdownHook(it) }
        } else null
    var completed = false
    try {
      return doScaffold(c).also { completed = true }
    } finally {
      if (!completed && createdByUs && c.projectDir.exists()) {
        try {
          c.projectDir.deleteRecursively()
        } catch (_: java.io.IOException) {
          // best-effort
        }
      }
      scaffoldInProgress = null
      wrapperProcess = null
      if (hook != null) {
        try {
          Runtime.getRuntime().removeShutdownHook(hook)
        } catch (_: IllegalStateException) {
          // JVM already shutting down
        }
      }
    }
  }

  private fun doScaffold(c: ProjectConfig): Boolean {
    val packagePath = c.packageName.replace(".", "/")
    val srcMain = if (c.useKotlin) "src/main/kotlin/$packagePath" else "src/main/java/$packagePath"
    val srcTest = if (c.useKotlin) "src/test/kotlin/$packagePath" else "src/test/java/$packagePath"
    val srcResources = "src/main/resources"

    // writeTemplate creates parent directories for each file, so only the standalone .paperplane
    // directory (which has no files written into it here) needs an explicit mkdirs.
    File(c.projectDir, ".paperplane").mkdirs()

    ProjectTemplates.writeTemplate(
        c.projectDir,
        "build.gradle.kts",
        ProjectTemplates.buildGradle(
            c.className,
            c.packageName,
            c.author,
            c.paperVersion,
            c.useKotlin,
        ),
    )
    ProjectTemplates.writeTemplate(
        c.projectDir,
        "settings.gradle.kts",
        ProjectTemplates.settingsGradle(c.projectName),
    )

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
        c.projectDir,
        "paperplane.yml",
        ProjectTemplates.paperplaneYml(c.paperVersion, c.devMode, c.jbr),
    )
    ProjectTemplates.writeTemplate(c.projectDir, ".gitignore", ProjectTemplates.gitignore())
    ProjectTemplates.writeTemplate(
        c.projectDir,
        "README.md",
        ProjectTemplates.readme(c.displayName),
    )
    ProjectTemplates.writeTemplate(
        c.projectDir,
        ".vscode/extensions.json",
        ProjectTemplates.vscodeExtensions(),
    )
    ProjectTemplates.writeTemplate(
        c.projectDir,
        ".vscode/settings.json",
        ProjectTemplates.vscodeSettings(),
    )

    val wp =
        ProcessBuilder("gradle", "wrapper", "--gradle-version", Versions.GRADLE_WRAPPER)
            .directory(c.projectDir)
            .redirectErrorStream(true)
            .start()
    wrapperProcess = wp
    val finished = wp.waitFor(WRAPPER_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    return if (!finished) {
      wp.destroyForcibly()
      false
    } else {
      wp.exitValue() == 0
    }
  }
}

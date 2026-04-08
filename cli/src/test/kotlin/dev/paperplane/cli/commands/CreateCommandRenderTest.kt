package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.paperplane.cli.ui.FakeReader
import dev.paperplane.cli.ui.InteractivePrompts
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Visual regression tests for [CreateCommand]'s non-interactive path.
 *
 * Uses a test subclass that overrides `runGradleWrapper` so no real `gradle wrapper` subprocess
 * fires. Tests always pass `--paper 1.21.4` to avoid the network call to `PaperVersionResolver`.
 *
 * The interactive prompt path is exercised separately by [InteractivePromptsRenderTest] and
 * [InteractivePromptsTest]; this file focuses on the scaffold output rendering.
 */
class CreateCommandRenderTest {

  @TempDir lateinit var tempDir: File

  private val originalUserDir = System.getProperty("user.dir")

  // Use canonicalPath so the user.dir we set matches the canonical filesystem path the
  // command's File operations resolve to (avoids macOS /var → /private/var symlink mismatch).
  private val canonicalTempDir: File by lazy { tempDir.canonicalFile }

  @BeforeEach
  fun setUp() {
    System.setProperty("user.dir", canonicalTempDir.absolutePath)
  }

  @AfterEach
  fun tearDown() {
    System.setProperty("user.dir", originalUserDir)
  }

  /** Test subclass that stubs out the gradle wrapper subprocess. */
  private class TestableCreateCommand(
      ui: TerminalUI,
      prompts: InteractivePrompts,
      var nextWrapperResult: Boolean = true,
  ) : CreateCommand(ui, prompts) {
    override fun runGradleWrapper(projectDir: File): Boolean = nextWrapperResult
  }

  private fun newCommand(
      wrapperResult: Boolean = true
  ): Triple<TestableCreateCommand, RecordingTerminal, InteractivePrompts> {
    val terminal = RecordingTerminal(scriptedReader = FakeReader(emptyList()))
    val ui = TerminalUI(terminal)
    val prompts = InteractivePrompts(terminal)
    return Triple(TestableCreateCommand(ui, prompts, wrapperResult), terminal, prompts)
  }

  // CreateCommand uses `File(slug)` (a relative path) for the project dir, which resolves
  // unreliably under test JVM cwd. We pass absolute paths as slugs to avoid the issue.

  // ── Happy path ─────────────────────────────────────────────────────

  @Test
  fun `non-interactive create scaffolds the project and prints next-step block`() {
    val projectDir = File(canonicalTempDir, "my-plugin")
    val (cmd, t, _) = newCommand()
    cmd.parse(listOf(projectDir.absolutePath, "--paper", "1.21.4", "--name", "My Plugin"))

    assertTrue(projectDir.exists(), "project directory should be created")
    assertTrue(File(projectDir, "build.gradle.kts").exists())
    assertTrue(File(projectDir, "paperplane.yml").exists())
    assertTrue(File(projectDir, "src/main/java").walkTopDown().any { it.name.endsWith(".java") })

    // Output blocks: header + spinner + success message + next-step block
    assertTrue(t.writes.any { it.contains("cd") && it.contains("switch to your project folder") })
    assertTrue(t.writes.any { it.contains("ppl") && it.contains("launch the dev server") })
  }

  // ── Already exists ─────────────────────────────────────────────────

  @Test
  fun `existing directory prints error and aborts`() {
    val projectDir = File(canonicalTempDir, "existing").apply { mkdirs() }
    val (cmd, t, _) = newCommand()
    cmd.parse(listOf(projectDir.absolutePath, "--paper", "1.21.4"))
    assertTrue(t.writes.any { it.contains("already exists") })
  }

  // ── Wrapper failure ────────────────────────────────────────────────

  @Test
  fun `failed gradle wrapper prints wrapper-failed error but still scaffolds`() {
    val projectDir = File(canonicalTempDir, "my-plugin")
    val (cmd, t, _) = newCommand(wrapperResult = false)
    cmd.parse(listOf(projectDir.absolutePath, "--paper", "1.21.4", "--name", "My Plugin"))

    assertTrue(projectDir.exists(), "scaffolding should still happen")
    assertTrue(t.writes.any { it.contains("Gradle wrapper failed") })
  }

  // ── --kotlin flag ──────────────────────────────────────────────────

  @Test
  fun `kotlin flag scaffolds Kotlin sources`() {
    val projectDir = File(canonicalTempDir, "kt-plugin")
    val (cmd, _, _) = newCommand()
    cmd.parse(
        listOf(projectDir.absolutePath, "--paper", "1.21.4", "--name", "Kt Plugin", "--kotlin")
    )

    val srcMain = File(projectDir, "src/main/kotlin")
    assertTrue(srcMain.exists())
    assertTrue(srcMain.walkTopDown().any { it.name.endsWith(".kt") })
    // Java source dir should NOT be present.
    assertFalse(File(projectDir, "src/main/java").exists())
  }

  // ── Custom plugin name and author ──────────────────────────────────

  @Test
  fun `name and author flags propagate into the build file`() {
    val projectDir = File(canonicalTempDir, "my-slug")
    val (cmd, _, _) = newCommand()
    cmd.parse(
        listOf(
            projectDir.absolutePath,
            "--paper",
            "1.21.4",
            "--name",
            "My Display Name",
            "--author",
            "Alice",
        )
    )
    val build = File(projectDir, "build.gradle.kts").readText()
    assertTrue(build.contains("Alice") || build.contains("alice"))
  }
}

package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.paperplane.cli.testing.RenderTestBase
import dev.paperplane.cli.ui.InteractivePrompts
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Visual regression tests for [CreateCommand]'s non-interactive path.
 *
 * Uses a test subclass that overrides `runGradleWrapper` so no real `gradle wrapper` subprocess
 * fires. Tests always pass `--paper 1.21.4` to avoid the network call to `PaperVersionResolver`.
 *
 * The interactive prompt path is exercised separately by [InteractivePromptsRenderTest] and
 * [InteractivePromptsTest]; this file focuses on the scaffold output rendering.
 */
class CreateCommandRenderTest : RenderTestBase() {

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
    val (ui, terminal) = newUi()
    val prompts = newPrompts(terminal)
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

  // ── Rollback ───────────────────────────────────────────────────────

  /**
   * Test subclass whose `runGradleWrapper` throws. This is the only injection point available to
   * simulate a mid-scaffold failure without stubbing out template writes — by the time
   * `runGradleWrapper` fires, `doScaffold` has already written build/source/config files into the
   * new directory, so rollback has real work to do.
   */
  private class SimulatedWrapperCrash : RuntimeException("simulated wrapper crash")

  private class ThrowingCreateCommand(ui: TerminalUI, prompts: InteractivePrompts) :
      CreateCommand(ui, prompts) {
    override fun runGradleWrapper(projectDir: File): Boolean = throw SimulatedWrapperCrash()
  }

  @Test
  fun `rollback deletes scaffolded directory when wrapper throws`() {
    val projectDir = File(canonicalTempDir, "doomed-plugin")
    val (ui, terminal) = newUi()
    val cmd = ThrowingCreateCommand(ui, newPrompts(terminal))

    assertThrows(SimulatedWrapperCrash::class.java) {
      cmd.parse(listOf(projectDir.absolutePath, "--paper", "1.21.4", "--name", "Doomed"))
    }

    assertFalse(
        projectDir.exists(),
        "rollback should have deleted the directory it created mid-scaffold",
    )
  }

  @Test
  fun `successful scaffold leaves directory intact and does not roll back`() {
    val projectDir = File(canonicalTempDir, "keeper")
    val (cmd, _, _) = newCommand()
    cmd.parse(listOf(projectDir.absolutePath, "--paper", "1.21.4", "--name", "Keeper"))

    assertTrue(projectDir.exists())
    // Running the command a second time should hit the "already exists" branch, proving the
    // happy-path finally block reset scaffoldState cleanly (a stale InProgress would have been
    // wiped by a shutdown hook or subsequent rollback call).
    val (cmd2, t2, _) = newCommand()
    cmd2.parse(listOf(projectDir.absolutePath, "--paper", "1.21.4", "--name", "Keeper"))
    assertTrue(projectDir.exists(), "pre-existing directory must not be rolled back")
    assertTrue(t2.writes.any { it.contains("already exists") })
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

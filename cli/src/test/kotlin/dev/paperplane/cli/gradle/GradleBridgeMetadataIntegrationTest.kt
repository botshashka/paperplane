package dev.paperplane.cli.gradle

import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end integration tests that drive the real Gradle Tooling API against tiny scratch
 * projects to verify [GradleBridge.metadata]'s exception classification.
 *
 * These tests close the gap left by `GradleBridgeTest` (which only exercises message-string
 * helpers in isolation) and the mode render tests (which fake the bridge entirely). The scenario
 * they protect against is concrete: a future Gradle version drifts its error wording in a way
 * that makes [GradleBridge.isTaskNotFoundMessage] return false for a real "task not found"
 * exception, or true for a real compile-error exception. Either drift would silently regress the
 * `ppl dev` UX (compile errors falsely routed to "ppl init / ppl create" hints, or vice versa)
 * with no unit-test failure.
 *
 * Tagged `slow` because each test spins up a Gradle daemon and configures a fresh project — first
 * run on a machine can take ~30s. The Tooling API client version (`libs.versions.toml`:
 * gradle-tooling) determines the daemon version used when the scratch project has no wrapper.
 */
@Tag("slow")
class GradleBridgeMetadataIntegrationTest {

  @TempDir lateinit var tempDir: File

  private fun newBridge() = GradleBridge(tempDir, TerminalUI(RecordingTerminal()))

  /**
   * Minimal Java project with **no** `ppMetadata` task. The Tooling API throws a
   * GradleConnectionException whose cause chain mentions "Task 'ppMetadata' not found"; this
   * verifies [GradleBridge.isTaskNotFoundMessage] still matches that real wording end-to-end.
   */
  @Test
  fun `metadata returns PluginNotApplied when ppMetadata task is absent`() {
    File(tempDir, "settings.gradle.kts").writeText("rootProject.name = \"scratch\"\n")
    File(tempDir, "build.gradle.kts").writeText("plugins { java }\n")

    newBridge().use { bridge -> assertEquals(MetadataResult.PluginNotApplied, bridge.metadata()) }
  }

  /**
   * Java project that declares `ppMetadata` (transitively depending on `compileJava`, mirroring
   * the production gradle plugin) plus a deliberate syntax error in source. Verifies that a real
   * compile-error exception chain does **not** match [GradleBridge.isTaskNotFoundMessage] and is
   * therefore routed to [MetadataResult.TaskFailed], not [MetadataResult.PluginNotApplied]. This
   * is the regression guard for the "ppl dev on a project with a typo bypasses fix-recovery" bug.
   */
  @Test
  fun `metadata returns TaskFailed when ppMetadata exists but compile fails`() {
    File(tempDir, "settings.gradle.kts").writeText("rootProject.name = \"scratch\"\n")
    File(tempDir, "build.gradle.kts")
        .writeText(
            """
            plugins { java }
            tasks.register("ppMetadata") {
              dependsOn(tasks.named("compileJava"))
            }
            """
                .trimIndent()
        )
    val srcDir = File(tempDir, "src/main/java/com/example").apply { mkdirs() }
    File(srcDir, "Bad.java").writeText("package com.example; public class Bad { void x { } }\n")

    val terminal = RecordingTerminal()
    val ui = TerminalUI(terminal)
    GradleBridge(tempDir, ui).use { bridge ->
      assertEquals(MetadataResult.TaskFailed, bridge.metadata())
    }
    // parseBuildErrors should have surfaced the compile error to the user — confirms the catch
    // branch ran and the user gets a useful message, not just silent failure.
    assertTrue(
        terminal.writes.any { it.contains("Bad.java") },
        "expected compile error file path to be rendered, got: ${terminal.writes}",
    )
  }
}

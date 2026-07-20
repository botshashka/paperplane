package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.paperplane.cli.testing.RenderTestBase
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.assertEmittedInOrder
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Visual regression tests for [InitCommand]. Drives the command via `parse(emptyList())` against a
 * temp project directory.
 *
 * Tests always supply a `paper-api:N.N.N-R...` pattern in the build file so the command's fallback
 * path through `PaperVersionResolver().resolveLatest()` (a network call) never fires.
 */
class InitCommandRenderTest : RenderTestBase() {

  private fun newCommand(): Pair<InitCommand, RecordingTerminal> {
    val (ui, terminal) = newUi()
    return InitCommand(ui) to terminal
  }

  private fun writeKtsBuildFile(paperVersion: String = "1.21.4") {
    File(canonicalTempDir, "build.gradle.kts")
        .writeText(
            """
            plugins {
                java
            }
            dependencies {
                compileOnly("io.papermc.paper:paper-api:$paperVersion-R0.1-SNAPSHOT")
            }
            """
                .trimIndent()
        )
  }

  // ── No build file ──────────────────────────────────────────────────

  @Test
  fun `no build file prints error`() {
    val (cmd, t) = newCommand()
    cmd.parse(emptyList())
    assertTrue(t.writes.any { it.contains("No build.gradle or build.gradle.kts found") })
  }

  // ── Happy path: KTS build file gets the plugin ─────────────────────

  @Test
  fun `kts build file gets PaperPlane plugin and config files`() {
    writeKtsBuildFile()
    val (cmd, t) = newCommand()
    cmd.parse(emptyList())

    val updated = File(canonicalTempDir, "build.gradle.kts").readText()
    assertTrue(updated.contains("dev.paperplane"), "plugin entry should be added")

    val configFile = File(canonicalTempDir, "paperplane.yml")
    assertTrue(configFile.exists(), "paperplane.yml should be created")

    t.assertEmittedInOrder(
        "Found build.gradle.kts",
        "Added PaperPlane plugin",
        "paperplane.yml",
        "PaperPlane setup complete",
    )
  }

  // ── Idempotent re-run ──────────────────────────────────────────────

  @Test
  fun `running init twice does not re-add the plugin`() {
    writeKtsBuildFile()
    val (cmd1, _) = newCommand()
    cmd1.parse(emptyList())

    val (cmd2, t2) = newCommand()
    cmd2.parse(emptyList())

    assertTrue(t2.writes.any { it.contains("PaperPlane plugin already applied") })
    // Second run should NOT also report "Added PaperPlane plugin"
    assertFalse(t2.writes.any { it.contains("Added PaperPlane plugin to") })
  }

  // ── Groovy build file flavor ───────────────────────────────────────

  @Test
  fun `groovy build file is also recognized`() {
    File(canonicalTempDir, "build.gradle")
        .writeText(
            """
            plugins {
                id 'java'
            }
            dependencies {
                compileOnly 'io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT'
            }
            """
                .trimIndent()
        )
    val (cmd, t) = newCommand()
    cmd.parse(emptyList())
    assertTrue(t.writes.any { it.contains("Found build.gradle") })
    assertTrue(File(canonicalTempDir, "build.gradle").readText().contains("dev.paperplane"))
  }

  // ── paperplane.yml is preserved if already present ────────────────

  @Test
  fun `existing paperplane yml is left alone`() {
    writeKtsBuildFile()
    val existing = File(canonicalTempDir, "paperplane.yml").apply { writeText("existing: true\n") }
    val (cmd, t) = newCommand()
    cmd.parse(emptyList())
    assertTrue(t.writes.any { it.contains("paperplane.yml already exists") })
    assertTrue(existing.readText().contains("existing: true"), "existing yml should be preserved")
  }

  // ── .gitignore entry is added ──────────────────────────────────────

  @Test
  fun `gitignore gets a paperplane entry when one exists`() {
    writeKtsBuildFile()
    val gitignore = File(canonicalTempDir, ".gitignore").apply { writeText("# existing\nbuild/\n") }
    val (cmd, t) = newCommand()
    cmd.parse(emptyList())
    assertTrue(gitignore.readText().contains(".paperplane/"))
    assertTrue(t.writes.any { it.contains("Added .paperplane/ to .gitignore") })
  }
}

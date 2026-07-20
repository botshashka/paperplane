package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.testing.FakeGradleBridge
import dev.paperplane.cli.testing.RenderTestBase
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.ui.assertEmittedInOrder
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Visual regression tests for [BuildCommand].
 *
 * Subclasses [BuildCommand] to inject a [FakeGradleBridge] via `newGradleBridge()`. The "build"
 * itself is faked — `gradle.metadata()` returns a scripted [ProjectMetadata] and the test writes a
 * real file at the metadata's `jarPath` so the rendered size + path lines have something to read.
 */
class BuildCommandRenderTest : RenderTestBase() {

  private class TestableBuildCommand(
      ui: TerminalUI,
      private val fake: FakeGradleBridge,
  ) : BuildCommand(ui) {
    override fun newGradleBridge(): GradleBridge = fake
  }

  private fun newCommand(): Triple<TestableBuildCommand, RecordingTerminal, FakeGradleBridge> {
    val (ui, terminal) = newUi()
    val fake = FakeGradleBridge(canonicalTempDir, ui)
    return Triple(TestableBuildCommand(ui, fake), terminal, fake)
  }

  /** Writes a fake jar at `<projectDir>/<jarPath>` and returns the absolute file. */
  private fun seedJar(jarPath: String, contentSize: Int = 2048): File {
    val jar = File(canonicalTempDir, jarPath)
    jar.parentFile.mkdirs()
    jar.writeBytes(ByteArray(contentSize) { 0 })
    return jar
  }

  private fun metadata(jarPath: String = "build/libs/sample-1.0.jar") =
      ProjectMetadata(
          jarPath = jarPath,
          paperApiVersion = "1.21.4",
          mainClass = "com.example.Sample",
          pluginName = "Sample",
          projectDir = canonicalTempDir.absolutePath,
          version = "1.0",
      )

  // ── Happy path ─────────────────────────────────────────────────────

  @Test
  fun `happy path renders Built plus output and size`() {
    val (cmd, t, fake) = newCommand()
    seedJar("build/libs/sample-1.0.jar")
    fake.nextMetadata = metadata()
    cmd.parse(emptyList())

    assertTrue(fake.calls.contains("metadata"))
    t.assertEmittedInOrder("Built Sample 1.0", "Output", "build/libs/sample-1.0.jar", "Size")
  }

  // ── Build failure ──────────────────────────────────────────────────

  @Test
  fun `metadata failure renders Build failed and no Built line`() {
    val (cmd, t, fake) = newCommand()
    fake.nextMetadata = null
    cmd.parse(emptyList())

    assertTrue(t.writes.any { it.contains("Build failed") })
    assertFalse(t.writes.any { it.contains("Built ") })
  }

  // ── --clean runs clean before build ────────────────────────────────

  @Test
  fun `clean flag runs clean before metadata`() {
    val (cmd, t, fake) = newCommand()
    seedJar("build/libs/sample-1.0.jar")
    fake.nextMetadata = metadata()
    cmd.parse(listOf("--clean"))

    val taskCalls = fake.calls.filter { it == "clean" || it == "metadata" }
    assertEquals(listOf("clean", "metadata"), taskCalls)
    assertTrue(t.writes.any { it.contains("Built Sample 1.0") })
  }

  // ── --clean failure short-circuits ─────────────────────────────────

  @Test
  fun `clean failure short circuits without invoking metadata`() {
    val (cmd, t, fake) = newCommand()
    fake.nextCleanResult = false
    cmd.parse(listOf("--clean"))

    assertTrue(t.writes.any { it.contains("Clean failed") })
    assertFalse(fake.calls.contains("metadata"), "metadata should not have been invoked")
    assertFalse(t.writes.any { it.contains("Built ") })
  }

  // ── --output copies the jar ────────────────────────────────────────

  @Test
  fun `output flag copies built jar to destination directory`() {
    val (cmd, t, fake) = newCommand()
    val jar = seedJar("build/libs/sample-1.0.jar")
    fake.nextMetadata = metadata()
    val outputDir = File(canonicalTempDir, "dist")
    cmd.parse(listOf("--output", outputDir.absolutePath))

    val expected = File(outputDir, jar.name)
    assertTrue(expected.exists(), "expected jar to be copied to ${expected.path}")
    assertEquals(jar.length(), expected.length())
    assertTrue(t.writes.any { it.contains("Copied to") })
  }

  // ── --output creates the directory if missing ──────────────────────

  @Test
  fun `output flag creates destination directory if missing`() {
    val (cmd, _, fake) = newCommand()
    seedJar("build/libs/sample-1.0.jar")
    fake.nextMetadata = metadata()
    val outputDir = File(canonicalTempDir, "nested/dist")
    assertFalse(outputDir.exists())
    cmd.parse(listOf("--output", outputDir.absolutePath))

    assertTrue(outputDir.isDirectory)
    assertTrue(File(outputDir, "sample-1.0.jar").exists())
  }

  // ── --output prints absolute path when destination is outside cwd ──

  @Test
  fun `output line falls back to absolute path when destination is outside cwd`(
      @org.junit.jupiter.api.io.TempDir externalTempDir: File
  ) {
    val (cmd, t, fake) = newCommand()
    seedJar("build/libs/sample-1.0.jar")
    fake.nextMetadata = metadata()
    // externalTempDir lives outside canonicalTempDir → relativeTo would emit "../../..".
    // Should fall back to absolute path instead.
    val outputDir = File(externalTempDir.canonicalFile, "dist")
    cmd.parse(listOf("--output", outputDir.absolutePath))

    val copiedLine = t.writes.firstOrNull { it.contains("Copied to") }
    assertTrue(copiedLine != null, "expected 'Copied to' line in output")
    assertFalse(
        copiedLine!!.contains(".."),
        "expected absolute path fallback, got line containing '..': $copiedLine",
    )
    assertTrue(
        copiedLine.contains(outputDir.absolutePath),
        "expected absolute path in output line, got: $copiedLine",
    )
  }

  // ── --output errors when the built jar is missing ──────────────────

  @Test
  fun `output flag prints error when built jar is missing on disk`() {
    val (cmd, t, fake) = newCommand()
    // Note: NO seedJar() call — metadata claims a jarPath that doesn't exist.
    fake.nextMetadata = metadata()
    val outputDir = File(canonicalTempDir, "dist")
    cmd.parse(listOf("--output", outputDir.absolutePath))

    assertTrue(t.writes.any { it.contains("Built jar not found") })
    assertFalse(File(outputDir, "sample-1.0.jar").exists())
  }

  // ── Size formatting branches ───────────────────────────────────────

  @Test
  fun `size renders in bytes for tiny jars`() {
    val (cmd, t, fake) = newCommand()
    seedJar("build/libs/sample-1.0.jar", contentSize = 512)
    fake.nextMetadata = metadata()
    cmd.parse(emptyList())

    assertTrue(
        t.writes.any { it.contains("Size") && it.contains("512 B") },
        "expected size line with bytes",
    )
  }

  @Test
  fun `size renders in megabytes for large jars`() {
    val (cmd, t, fake) = newCommand()
    // 2 MB exactly — exercises the MB branch (>= 1_048_576 bytes)
    seedJar("build/libs/sample-1.0.jar", contentSize = 2 * 1024 * 1024)
    fake.nextMetadata = metadata()
    cmd.parse(emptyList())

    assertTrue(
        t.writes.any { it.contains("Size") && it.contains("MB") },
        "expected size line with MB unit",
    )
  }

  // ── Spinner messages ───────────────────────────────────────────────

  @Test
  fun `spinner shows Building message on plain build`() {
    val (cmd, t, fake) = newCommand()
    seedJar("build/libs/sample-1.0.jar")
    fake.nextMetadata = metadata()
    cmd.parse(emptyList())

    assertTrue(
        t.writes.any { it.contains("Building") },
        "expected 'Building…' spinner frame to appear in output",
    )
  }

  @Test
  fun `spinner shows Cleaning message when clean flag is set`() {
    val (cmd, t, fake) = newCommand()
    seedJar("build/libs/sample-1.0.jar")
    fake.nextMetadata = metadata()
    cmd.parse(listOf("--clean"))

    assertTrue(
        t.writes.any { it.contains("Cleaning") },
        "expected 'Cleaning…' spinner frame to appear in output",
    )
  }
}

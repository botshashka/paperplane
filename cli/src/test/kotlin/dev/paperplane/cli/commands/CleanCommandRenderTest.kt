package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.paperplane.cli.ui.FakeReader
import dev.paperplane.cli.ui.InteractivePrompts
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.ui.assertEmittedInOrder
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Visual regression tests for [CleanCommand].
 *
 * Drives the command end-to-end via `command.parse(args)` against a temp project directory, with
 * `user.dir` swapped in [BeforeEach] so the command's `File(user.dir)` resolves to the temp dir.
 * `prompts.confirm()` reads from `System.in`, so confirmation tests redirect stdin.
 */
class CleanCommandRenderTest {

  @TempDir lateinit var tempDir: File

  private val originalUserDir = System.getProperty("user.dir")
  private val originalIn = System.`in`

  @BeforeEach
  fun setUp() {
    System.setProperty("user.dir", tempDir.absolutePath)
  }

  @AfterEach
  fun tearDown() {
    System.setProperty("user.dir", originalUserDir)
    System.setIn(originalIn)
  }

  private fun newCommand(): Pair<CleanCommand, RecordingTerminal> {
    val terminal = RecordingTerminal(scriptedReader = FakeReader(emptyList()))
    val ui = TerminalUI(terminal)
    val prompts = InteractivePrompts(terminal)
    return CleanCommand(ui, prompts) to terminal
  }

  private fun stubStdin(input: String) {
    System.setIn(ByteArrayInputStream(input.toByteArray()))
  }

  // ── No .paperplane/ dir ────────────────────────────────────────────

  @Test
  fun `no paperplane dir prints nothing-to-clean error`() {
    val (cmd, t) = newCommand()
    cmd.parse(emptyList())
    assertTrue(
        t.writes.any { it.contains("No .paperplane/ directory found — nothing to clean") },
        "Expected the not-found error message in $t.writes",
    )
  }

  // ── Empty .paperplane/ ─────────────────────────────────────────────

  @Test
  fun `existing paperplane dir with no subdirs prints Nothing to clean`() {
    File(tempDir, ".paperplane").mkdirs()
    val (cmd, t) = newCommand()
    cmd.parse(emptyList())
    assertTrue(t.writes.any { it.contains("Nothing to clean") })
  }

  // ── Force flag ─────────────────────────────────────────────────────

  @Test
  fun `force flag deletes server dir without confirmation prompt`() {
    val serverDir = File(tempDir, ".paperplane/server").apply { mkdirs() }
    File(serverDir, "marker").writeText("x")
    val (cmd, t) = newCommand()
    cmd.parse(listOf("--force"))
    assertFalse(serverDir.exists(), "server dir should have been deleted")
    t.assertEmittedInOrder("This will delete:", "Deleted server/", "Clean complete")
  }

  // ── Interactive confirm: accept ────────────────────────────────────

  @Test
  fun `confirmation accepted via stdin proceeds with delete`() {
    val serverDir = File(tempDir, ".paperplane/server").apply { mkdirs() }
    stubStdin("y\n")
    val (cmd, t) = newCommand()
    cmd.parse(emptyList())
    assertFalse(serverDir.exists())
    assertTrue(t.writes.any { it.contains("Deleted server/") })
    assertTrue(t.writes.any { it.contains("Clean complete") })
  }

  // ── Interactive confirm: decline ───────────────────────────────────

  @Test
  fun `confirmation declined leaves files intact and prints Cancelled`() {
    val serverDir = File(tempDir, ".paperplane/server").apply { mkdirs() }
    stubStdin("n\n")
    val (cmd, t) = newCommand()
    cmd.parse(emptyList())
    assertTrue(serverDir.exists(), "server dir should NOT have been deleted")
    assertTrue(t.writes.any { it.contains("Cancelled") })
    assertFalse(t.writes.any { it.contains("Deleted") })
  }

  // ── --all flag includes cache ──────────────────────────────────────

  @Test
  fun `all flag includes the cache dir in deletion`() {
    val cacheDir = File(tempDir, ".paperplane/cache").apply { mkdirs() }
    val serverDir = File(tempDir, ".paperplane/server").apply { mkdirs() }
    val (cmd, t) = newCommand()
    cmd.parse(listOf("--force", "--all"))
    assertFalse(cacheDir.exists())
    assertFalse(serverDir.exists())
    t.assertEmittedInOrder("Deleted server/", "Deleted cache/")
  }

  // ── Cache preserved when --all not set ─────────────────────────────

  @Test
  fun `cache is preserved when --all is not set`() {
    val cacheDir = File(tempDir, ".paperplane/cache").apply { mkdirs() }
    File(tempDir, ".paperplane/server").mkdirs()
    val (cmd, t) = newCommand()
    cmd.parse(listOf("--force"))
    assertTrue(cacheDir.exists(), "cache dir should be preserved without --all")
    assertTrue(t.writes.any { it.contains("Cache preserved") && it.contains("Use --all") })
  }
}

package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.testing.FakeGradleBridge
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Visual regression tests for [TestCommand].
 *
 * Subclasses [TestCommand] to inject a [FakeGradleBridge] via `newGradleBridge()` and writes
 * pre-baked JUnit XML files into `build/test-results/test/` so the parser has something to read.
 * The actual `gradle.test()` invocation is faked; the XML files are the test fixture.
 *
 * The watch mode is not exercised here because it spawns a real `FileWatcher` and blocks in
 * `Thread.sleep(WATCH_POLL_INTERVAL_MS)` until interrupted.
 */
class TestCommandRenderTest {

  @TempDir lateinit var tempDir: File

  private val originalUserDir = System.getProperty("user.dir")

  @BeforeEach
  fun setUp() {
    System.setProperty("user.dir", tempDir.absolutePath)
  }

  @AfterEach
  fun tearDown() {
    System.setProperty("user.dir", originalUserDir)
  }

  private class TestableTestCommand(
      ui: TerminalUI,
      private val fake: FakeGradleBridge,
  ) : TestCommand(ui) {
    override fun newGradleBridge(): GradleBridge = fake
  }

  private fun newCommand(
      buildResult: Boolean = true
  ): Triple<TestableTestCommand, RecordingTerminal, FakeGradleBridge> {
    val terminal = RecordingTerminal()
    val ui = TerminalUI(terminal)
    val fake = FakeGradleBridge(tempDir, ui, nextTestResult = buildResult)
    return Triple(TestableTestCommand(ui, fake), terminal, fake)
  }

  private fun writeJunitXml(suiteName: String, vararg cases: Triple<String, Double, String?>) {
    val resultsDir = File(tempDir, "build/test-results/test").apply { mkdirs() }
    val xml = buildString {
      append("<?xml version=\"1.0\"?>\n")
      append("<testsuite name=\"$suiteName\" time=\"")
      append(cases.sumOf { it.second })
      append("\">\n")
      for ((name, time, failure) in cases) {
        append("  <testcase name=\"$name\" time=\"$time\">")
        if (failure != null) append("\n    <failure>$failure</failure>\n  ")
        append("</testcase>\n")
      }
      append("</testsuite>\n")
    }
    File(resultsDir, "TEST-$suiteName.xml").writeText(xml)
  }

  // ── All passing ────────────────────────────────────────────────────

  @Test
  fun `all passing renders the green summary`() {
    val (cmd, t, fake) = newCommand()
    fake.onTest = {
      writeJunitXml(
          "FooTest",
          Triple("addsTwoNumbers", 0.05, null),
          Triple("multipliesNumbers", 0.03, null),
      )
    }
    cmd.parse(emptyList())
    assertTrue(fake.calls.any { it.startsWith("test(quiet=true") })
    assertTrue(t.writes.any { it.contains("FooTest") })
    assertTrue(t.writes.any { it.contains("addsTwoNumbers") })
    assertTrue(t.writes.any { it.contains("2 passed") && it.contains("(2)") })
  }

  // ── Mixed pass/fail ────────────────────────────────────────────────

  @Test
  fun `mixed pass and fail renders both counts and the verbose hint`() {
    val (cmd, t, fake) = newCommand()
    fake.onTest = {
      writeJunitXml(
          "BarTest",
          Triple("works", 0.01, null),
          Triple("broken", 0.02, "expected 1 but was 2"),
      )
    }
    cmd.parse(emptyList())
    assertTrue(t.writes.any { it.contains("1 passed") && it.contains("1 failed") })
    assertTrue(t.writes.any { it.contains("Run with --verbose for full stack traces") })
  }

  // ── No results found ───────────────────────────────────────────────

  @Test
  fun `no results dir and failed build prints failure error`() {
    val (cmd, t, _) = newCommand(buildResult = false)
    cmd.parse(emptyList())
    assertTrue(t.writes.any { it.contains("Tests failed (no results found)") })
  }

  // ── Filter flag passes through ─────────────────────────────────────

  @Test
  fun `filter flag is forwarded to gradle test`() {
    val (cmd, _, fake) = newCommand()
    fake.onTest = { writeJunitXml("FilteredTest", Triple("specific", 0.01, null)) }
    cmd.parse(listOf("--filter", "specific"))
    assertTrue(fake.calls.any { it.contains("filter=specific") })
  }

  // ── Filter spinner message reflects the filter ─────────────────────

  @Test
  fun `filter changes the spinner message`() {
    val (cmd, t, fake) = newCommand()
    fake.onTest = { writeJunitXml("X", Triple("a", 0.01, null)) }
    cmd.parse(listOf("--filter", "myFilter"))
    // The spinner line "Running tests matching \"myFilter\"..." appears in the write log via
    // the redraw, even though it's later cleared by the spinner clear.
    assertTrue(t.writes.any { it.contains("Running tests matching") && it.contains("myFilter") })
  }

  // ── Verbose flag suppresses the verbose hint ───────────────────────

  @Test
  fun `verbose flag suppresses the verbose hint after a failure`() {
    val (cmd, t, fake) = newCommand()
    fake.onTest = { writeJunitXml("BarTest", Triple("broken", 0.02, "boom")) }
    cmd.parse(listOf("--verbose"))
    assertTrue(t.writes.any { it.contains("1 failed") })
    // The verbose-hint status line should NOT be present.
    assertTrue(t.writes.none { it.contains("Run with --verbose") })
  }
}

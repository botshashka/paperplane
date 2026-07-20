package dev.paperplane.cli.testing

import dev.paperplane.cli.ui.FakeReader
import dev.paperplane.cli.ui.InteractivePrompts
import dev.paperplane.cli.ui.RecordingTerminal
import dev.paperplane.cli.ui.TerminalUI
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

/**
 * Shared fixture for command visual-regression tests. Consolidates the boilerplate that every
 * `*CommandRenderTest` file used to duplicate:
 *
 * - `@TempDir` allocation + a lazy [canonicalTempDir] that side-steps the macOS `/var →
 *   /private/var` symlink (necessary whenever a test asserts paths the command derived from its own
 *   `user.dir`).
 * - `user.dir` save/restore around each test so a command's `File(System.getProperty("user.dir"))`
 *   resolves to the temp dir.
 * - `System.in` save/restore so tests that script stdin via [stubStdin] don't leak into siblings.
 * - A [newUi] factory that wires a [RecordingTerminal] to a [TerminalUI], and a [newPrompts]
 *   factory for tests that construct their own command instances.
 *
 * **Not used by dev-server mode tests** (`*ModeRenderTest`) — those have materially different
 * fixtures (fake gradle bridge, fake downloaders, no `user.dir` dependency). Keeping this base
 * scoped to the command render tests avoids forcing a one-size-fits-all abstraction.
 */
abstract class RenderTestBase {

  @TempDir protected lateinit var tempDir: File

  /**
   * Canonicalized [tempDir]. JUnit's `@TempDir` on macOS hands out paths under `/var/folders/...`,
   * which is a symlink to `/private/var/folders/...`. Commands that call
   * `File(System.getProperty("user.dir"))` and then resolve relative paths against it end up in the
   * canonical form, so path-equality assertions against the raw `tempDir` fail. Always use this.
   */
  protected val canonicalTempDir: File by lazy { tempDir.canonicalFile }

  private lateinit var originalUserDir: String
  private val originalStdin = System.`in`

  @BeforeEach
  fun setUpRenderTestBase() {
    originalUserDir = System.getProperty("user.dir")
    System.setProperty("user.dir", canonicalTempDir.absolutePath)
  }

  @AfterEach
  fun tearDownRenderTestBase() {
    System.setProperty("user.dir", originalUserDir)
    System.setIn(originalStdin)
  }

  /** Returns a fresh `(TerminalUI, RecordingTerminal)` pair backed by an empty scripted reader. */
  protected fun newUi(): Pair<TerminalUI, RecordingTerminal> {
    val terminal = RecordingTerminal(scriptedReader = FakeReader(emptyList()))
    return TerminalUI(terminal) to terminal
  }

  /** Returns an [InteractivePrompts] wired to [terminal]. */
  protected fun newPrompts(terminal: RecordingTerminal): InteractivePrompts =
      InteractivePrompts(terminal)

  /**
   * Replaces `System.in` with a fixed input string. Restored automatically in
   * [tearDownRenderTestBase].
   */
  protected fun stubStdin(input: String) {
    System.setIn(ByteArrayInputStream(input.toByteArray()))
  }
}

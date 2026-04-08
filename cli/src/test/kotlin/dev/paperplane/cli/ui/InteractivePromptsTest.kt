package dev.paperplane.cli.ui

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.jline.utils.NonBlockingReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [InteractivePrompts.readPromptLine] — the keystroke loop driving interactive text
 * prompts. The function is pure over a [NonBlockingReader], so it tests cleanly with a queue-backed
 * fake.
 *
 * Stdout is captured into a sink to keep ANSI escape sequences out of the test report.
 */
class InteractivePromptsTest {

  private val originalOut = System.out
  private val captured = ByteArrayOutputStream()
  private val prompts = InteractivePrompts(RecordingTerminal())

  @BeforeEach
  fun captureStdout() {
    System.setOut(PrintStream(captured, true, Charsets.UTF_8))
  }

  @AfterEach
  fun restoreStdout() {
    System.setOut(originalOut)
  }

  /** Minimal NonBlockingReader fake that dequeues code points from a fixed list. */
  private class FakeReader(codePoints: List<Int>) : NonBlockingReader() {
    private val queue = ArrayDeque(codePoints)

    override fun read(timeout: Long, isPeek: Boolean): Int {
      val v = queue.firstOrNull() ?: return EOF
      if (!isPeek) queue.removeFirst()
      return v
    }

    override fun readBuffered(b: CharArray?, off: Int, len: Int, timeout: Long): Int =
        throw UnsupportedOperationException("not used by readPromptLine")

    override fun close() {
      // no-op — fake has no resources
    }
  }

  // ── Plain ASCII input + Enter ──────────────────────────────────────

  @Test
  fun `typing then Enter returns the typed string`() {
    val reader = FakeReader("hi\r".map { it.code })
    val result = prompts.readPromptLine(default = null, reader = reader)
    assertEquals("hi", result)
  }

  @Test
  fun `Enter alone with default returns the default`() {
    val reader = FakeReader(listOf('\r'.code))
    val result = prompts.readPromptLine(default = "fallback", reader = reader)
    assertEquals("fallback", result)
  }

  @Test
  fun `Enter alone without default returns null so caller can re-render`() {
    val reader = FakeReader(listOf('\r'.code))
    val result = prompts.readPromptLine(default = null, reader = reader)
    assertNull(result)
  }

  @Test
  fun `LF (10) is treated as Enter`() {
    val reader = FakeReader("ok\n".map { it.code })
    assertEquals("ok", prompts.readPromptLine(null, reader))
  }

  // ── Non-ASCII input (regression for the step-1 bugfix) ─────────────

  @Test
  fun `Latin-1 character is accepted`() {
    val reader = FakeReader(listOf('Ë'.code, 'c'.code, 'h'.code, 'o'.code, '\r'.code))
    assertEquals("Ëcho", prompts.readPromptLine(null, reader))
  }

  @Test
  fun `CJK character is accepted`() {
    val reader = FakeReader(listOf('你'.code, '好'.code, '\r'.code))
    assertEquals("你好", prompts.readPromptLine(null, reader))
  }

  @Test
  fun `tab and other control chars are dropped`() {
    val reader = FakeReader(listOf('a'.code, '\t'.code, '\u0001'.code, 'b'.code, '\r'.code))
    assertEquals("ab", prompts.readPromptLine(null, reader))
  }

  @Test
  fun `DEL byte (127) is treated as backspace not as input`() {
    val reader = FakeReader(listOf('a'.code, 'b'.code, 127, '\r'.code))
    assertEquals("a", prompts.readPromptLine(null, reader))
  }

  // ── Backspace ──────────────────────────────────────────────────────

  @Test
  fun `backspace removes the last typed character`() {
    val reader = FakeReader(listOf('a'.code, 'b'.code, 'c'.code, 8, '\r'.code))
    assertEquals("ab", prompts.readPromptLine(null, reader))
  }

  @Test
  fun `backspace clears default placeholder before character input`() {
    // Default is shown as a placeholder. First backspace should clear it,
    // then normal typing replaces.
    val reader = FakeReader(listOf(8, 'x'.code, '\r'.code))
    assertEquals("x", prompts.readPromptLine(default = "default", reader = reader))
  }

  // ── Cancellation ───────────────────────────────────────────────────

  @Test
  fun `Ctrl+C throws PromptCancelledException`() {
    val reader = FakeReader(listOf('a'.code, 3))
    assertThrows(PromptCancelledException::class.java) { prompts.readPromptLine(null, reader) }
  }

  @Test
  fun `ESC throws PromptCancelledException`() {
    val reader = FakeReader(listOf('a'.code, 27))
    assertThrows(PromptCancelledException::class.java) { prompts.readPromptLine(null, reader) }
  }

  @Test
  fun `EOF throws PromptCancelledException`() {
    val reader = FakeReader(emptyList())
    assertThrows(PromptCancelledException::class.java) { prompts.readPromptLine(null, reader) }
  }
}

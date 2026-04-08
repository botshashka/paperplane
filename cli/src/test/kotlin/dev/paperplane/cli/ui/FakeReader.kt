package dev.paperplane.cli.ui

import org.jline.utils.NonBlockingReader

/**
 * Minimal scripted [NonBlockingReader] backed by a fixed list of code points. Each [read] call
 * dequeues the next code point; reading past the end returns `EOF` (-1). [peek] returns the head
 * without consuming it.
 *
 * Used by both `InteractivePromptsTest` (low-level keystroke loop unit tests) and
 * `InteractivePromptsRenderTest` (full prompt/select rendering integration tests). The latter
 * passes the reader into a [RecordingTerminal] so prompts and selects can be driven end-to-end.
 *
 * The `read(buf)` / `readBuffered` overloads are not implemented because the production code paths
 * under test never call them.
 */
class FakeReader(codePoints: List<Int>) : NonBlockingReader() {
  private val queue = ArrayDeque(codePoints)

  override fun read(timeout: Long, isPeek: Boolean): Int {
    val v = queue.firstOrNull() ?: return EOF
    if (!isPeek) queue.removeFirst()
    return v
  }

  override fun readBuffered(b: CharArray?, off: Int, len: Int, timeout: Long): Int =
      throw UnsupportedOperationException("not used by InteractivePrompts")

  override fun close() {
    // no-op — fake holds no resources
  }
}

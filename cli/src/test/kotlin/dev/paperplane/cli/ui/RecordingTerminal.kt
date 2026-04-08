package dev.paperplane.cli.ui

import org.jline.utils.NonBlockingReader

/**
 * Test fake [Terminal] that captures every write into a buffer instead of touching `System.out`.
 *
 * Tests construct a `RecordingTerminal()`, hand it to [TerminalUI] / [InteractivePrompts], drive
 * the code under test, and assert on [writes] (whole-line writes) or [raw] (the full unbuffered
 * stream including partial-line ANSI sequences).
 *
 * `isTty` defaults to true so the block-pinning code paths exercise — set [isTty] = false to test
 * the non-TTY scroll-commit path.
 *
 * Raw-mode and reader support are minimal: [enterRawMode] is a no-op `AutoCloseable`, and [reader]
 * returns a [NonBlockingReader] backed by an empty queue. Tests that drive interactive prompts pass
 * a real `NonBlockingReader` directly into the prompt's keystroke loop.
 */
class RecordingTerminal(override val isTty: Boolean = true) : Terminal {
  private val buffer = StringBuilder()
  private val lineWrites = mutableListOf<String>()

  /** Every line emitted via [writeLine], in order. */
  val writes: List<String>
    get() = lineWrites.toList()

  /** Full unbuffered output including partial-line writes (ANSI sequences, prompts). */
  val raw: String
    get() = buffer.toString()

  override fun write(text: String) {
    buffer.append(text)
  }

  override fun writeLine(text: String) {
    buffer.append(text).append('\n')
    lineWrites += text
  }

  override fun flush() {
    // no-op — buffer is in-memory, nothing to flush
  }

  override fun enterRawMode(): AutoCloseable = AutoCloseable {}

  override fun reader(): NonBlockingReader = EmptyReader

  /** Erases the recorded buffer and line list. Call between phases of a multi-step test. */
  fun clear() {
    buffer.setLength(0)
    lineWrites.clear()
  }

  private object EmptyReader : NonBlockingReader() {
    override fun read(timeout: Long, isPeek: Boolean): Int = -1

    override fun close() {
      // no-op — empty reader holds no resources
    }

    override fun readBuffered(buf: CharArray): Int = -1

    override fun readBuffered(buf: CharArray, offset: Int, length: Int, timeout: Long): Int = -1
  }
}

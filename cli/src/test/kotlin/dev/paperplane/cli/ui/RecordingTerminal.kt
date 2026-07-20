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
 * Pass a [scriptedReader] to drive `InteractivePrompts` keystroke input. Defaults to an empty
 * reader that always returns EOF, which is fine for `TerminalUI` tests. [enterRawMode] is a no-op
 * `AutoCloseable` — tests that care about raw-mode lifecycle can read [rawModeEntries].
 */
class RecordingTerminal(
    override val isTty: Boolean = true,
    override val width: Int = 80,
    private val scriptedReader: NonBlockingReader = EmptyReader,
) : Terminal {
  private val buffer = StringBuilder()
  private val lineWrites = mutableListOf<String>()
  private var rawModeEntryCount = 0

  /** Every line emitted via [writeLine], in order. */
  val writes: List<String>
    get() = lineWrites.toList()

  /** Full unbuffered output including partial-line writes (ANSI sequences, prompts). */
  val raw: String
    get() = buffer.toString()

  /** How many times [enterRawMode] has been invoked. Useful for asserting nested-view reuse. */
  val rawModeEntries: Int
    get() = rawModeEntryCount

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

  override fun enterRawMode(): AutoCloseable {
    rawModeEntryCount++
    return AutoCloseable {}
  }

  override fun reader(): NonBlockingReader = scriptedReader

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

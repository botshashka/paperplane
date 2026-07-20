package dev.paperplane.cli.ui

import org.jline.terminal.Attributes
import org.jline.terminal.Terminal as JlineTerminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.NonBlockingReader

/**
 * Production [Terminal] implementation — writes to `System.out`, reads from a lazily-built JLine
 * terminal.
 *
 * Exactly one instance exists in a running CLI process, constructed in `PaperPlane.main` and
 * threaded through every command, dev-server mode, and helper. Single-writer by convention:
 * [TerminalUI] and [InteractivePrompts] each take their own lock before calling in.
 *
 * ### Raw mode
 *
 * [enterRawMode] saves the current attributes, enters JLine raw mode, then clears the `ISIG` local
 * flag so `Ctrl+C` / `Ctrl+\` arrive as raw bytes in the keystroke loop instead of terminating the
 * JVM via signal. The returned [AutoCloseable] restores the saved attributes on close. Safe to call
 * from anywhere on the main thread; nested entry is handled by callers (see
 * [InteractivePrompts.beginInteractiveView]).
 */
class AnsiTerminal : Terminal {
  companion object {
    /** Fallback column count if JLine can't report a real size (dumb terminal, pre-init, etc.). */
    private const val DEFAULT_WIDTH = 80
  }

  override val isTty: Boolean = System.console() != null

  override val width: Int
    get() = jline().size.columns.takeIf { it > 0 } ?: DEFAULT_WIDTH

  private val jlineLock = Any()
  private var jlineInstance: JlineTerminal? = null

  private fun jline(): JlineTerminal =
      synchronized(jlineLock) {
        jlineInstance
            ?: TerminalBuilder.builder().system(true).dumb(true).build().also { jlineInstance = it }
      }

  override fun write(text: String) {
    print(text)
  }

  override fun writeLine(text: String) {
    println(text)
  }

  override fun flush() {
    System.out.flush()
  }

  override fun enterRawMode(): AutoCloseable {
    val t = jline()
    val saved = t.enterRawMode()
    // JLine leaves ISIG enabled by default; clear it so Ctrl+C arrives as byte 3 in the reader
    // instead of terminating the JVM before PromptCancelledException can propagate.
    val attrs = Attributes(t.attributes)
    attrs.setLocalFlag(Attributes.LocalFlag.ISIG, false)
    t.attributes = attrs
    return AutoCloseable {
      try {
        t.attributes = saved
      } catch (_: Exception) {
        // best-effort — terminal may already be torn down during shutdown
      }
    }
  }

  override fun reader(): NonBlockingReader = jline().reader()
}

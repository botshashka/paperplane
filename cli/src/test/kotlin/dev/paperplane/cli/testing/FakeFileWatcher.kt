package dev.paperplane.cli.testing

import dev.paperplane.cli.watcher.FileWatcher
import java.io.File

/**
 * Test fake [FileWatcher] that never spawns a polling thread. [start] and [stop] just record the
 * calls. Tests drive the watcher by calling [triggerChange] directly, which synchronously invokes
 * the registered callback with the supplied file list.
 *
 * Use this in dev-server mode tests to model file-change events without waiting for real disk
 * activity or debounce timers.
 */
class FakeFileWatcher(
    watchDir: File,
    debounceMs: Long = 0,
    onChange: (List<String>) -> Unit,
) : FileWatcher(watchDir, debounceMs, onChange) {

  /** Ordered log of `start` / `stop` calls. */
  val calls: MutableList<String> = mutableListOf()

  override fun start() {
    calls += "start"
  }

  override fun stop() {
    calls += "stop"
  }

  /** Synchronously fires the registered onChange callback with [files]. */
  fun triggerChange(files: List<String>) {
    onChange(files)
  }
}

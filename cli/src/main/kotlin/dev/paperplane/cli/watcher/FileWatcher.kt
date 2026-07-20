package dev.paperplane.cli.watcher

import dev.paperplane.cli.util.Platform
import java.io.File

open class FileWatcher(
    private val watchDir: File,
    private val debounceMs: Long = 2000,
    private val extraFiles: List<File> = emptyList(),
    protected val onChange: (List<String>) -> Unit,
) {
  companion object {
    private const val POLL_INTERVAL_MS = 500L
    private const val STOP_JOIN_TIMEOUT_MS = 2000L

    /**
     * Normalize a file path the same way the watcher does when emitting changed-file paths. Callers
     * comparing paths against the watcher's output (e.g. DevSession deciding whether a build-config
     * file changed) must run their candidate paths through this — on Windows the watcher
     * lowercases, so a case-mismatched check would silently miss changes.
     */
    fun normalizePath(path: String): String = if (Platform.isWindows) path.lowercase() else path
  }

  @Volatile private var running = false
  private var thread: Thread? = null

  open fun start() {
    running = true

    // Snapshot all file modification times
    var lastSnapshot = snapshot()
    var lastChangeTime = 0L
    val changedFiles = mutableSetOf<String>()

    thread =
        Thread(
                {
                  while (running) {
                    Thread.sleep(POLL_INTERVAL_MS)

                    val current = snapshot()
                    val newChanges = diff(lastSnapshot, current)
                    if (newChanges.isNotEmpty()) {
                      changedFiles.addAll(newChanges)
                      lastChangeTime = System.currentTimeMillis()
                    }
                    lastSnapshot = current

                    // Debounce: fire after quiet period
                    if (
                        changedFiles.isNotEmpty() &&
                            System.currentTimeMillis() - lastChangeTime >= debounceMs
                    ) {
                      val files = changedFiles.toList()
                      changedFiles.clear()
                      onChange(files)
                      // Files may change while onChange runs (a save mid-rebuild). Rebase the
                      // baseline to the post-handle state, but carry the diff into changedFiles —
                      // silently rebasing would swallow those edits and never rebuild them.
                      val post = snapshot()
                      val duringHandle = diff(lastSnapshot, post)
                      if (duringHandle.isNotEmpty()) {
                        changedFiles.addAll(duringHandle)
                        lastChangeTime = System.currentTimeMillis()
                      }
                      lastSnapshot = post
                    }
                  }
                },
                "file-watcher",
            )
            .apply { isDaemon = true }

    thread!!.start()
  }

  open fun stop() {
    running = false
    thread?.join(STOP_JOIN_TIMEOUT_MS)
  }

  /** Paths changed, added, or deleted between [prev] and [current]. */
  private fun diff(prev: Map<String, Long>, current: Map<String, Long>): Set<String> {
    val changed = mutableSetOf<String>()
    for ((path, modTime) in current) {
      if (prev[path] != modTime) changed.add(path)
    }
    for (path in prev.keys) {
      if (path !in current) changed.add(path)
    }
    return changed
  }

  private fun snapshot(): Map<String, Long> {
    val result = mutableMapOf<String, Long>()
    watchDir
        .walkTopDown()
        .onEnter { !shouldIgnoreDir(it.name) }
        .filter { it.isFile && !shouldIgnore(it.name) }
        .forEach { result[normalizePath(it.absolutePath)] = it.lastModified() }
    for (extra in extraFiles) {
      if (extra.isFile) {
        result[normalizePath(extra.absolutePath)] = extra.lastModified()
      }
    }
    return result
  }

  private fun shouldIgnore(name: String): Boolean {
    return name.endsWith(".class") || name.endsWith(".jar") || name.endsWith(".DS_Store")
  }

  private fun shouldIgnoreDir(name: String): Boolean {
    return name == "build" ||
        name == ".gradle" ||
        name == ".git" ||
        name == ".paperplane" ||
        name == "node_modules"
  }
}

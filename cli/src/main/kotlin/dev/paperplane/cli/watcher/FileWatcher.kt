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

                    // Find changed files
                    for ((path, modTime) in current) {
                      val prev = lastSnapshot[path]
                      if (prev == null || prev != modTime) {
                        changedFiles.add(path)
                        lastChangeTime = System.currentTimeMillis()
                      }
                    }
                    // Find deleted files
                    for (path in lastSnapshot.keys) {
                      if (path !in current) {
                        changedFiles.add(path)
                        lastChangeTime = System.currentTimeMillis()
                      }
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
                      // Re-snapshot after rebuild since build may have changed files
                      lastSnapshot = snapshot()
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

  private fun normalizePath(path: String): String =
      if (Platform.isWindows) path.lowercase() else path
}

package dev.paperplane.cli.server

import java.io.File
import java.io.IOException

/**
 * Copy-on-write sync for a single world directory — the world-refresh sync primitive, engine- and
 * mode-independent. Clones when the filesystem can and falls back to [IncrementalSync] when it
 * can't:
 * - **macOS:** `cp -c -R` (APFS clonefile — measured 5–58 ms even at 1 GB, gate 5). Apple's `cp -c`
 *   silently degrades to a real copy on non-clonefile filesystems, so "unsupported" is undetectable
 *   there; the degradation is bounded at plain-copy cost.
 * - **Linux:** `cp -a --reflink=always` (btrfs/XFS reflink — ~2 ms per 200 MB, gate 5). Fails
 *   outright on ext4 and on `cp`s without the flag, which routes to the fallback and is remembered
 *   so later syncs skip the doomed exec.
 * - **Windows and everything else:** the incremental copy is the supported tier, not an error
 *   (ReFS/Dev Drive CoW is an open research note). No process is ever exec'd here.
 *
 * The clone lands in a temp sibling and is swapped in only on success, so a failed clone leaves the
 * previous target intact for the incremental fallback to reconcile. After a successful clone, names
 * matching the skip predicate (lock files) are pruned from the result — a clone is a full-tree
 * replacement, so "skip" collapses to "must not survive in the copy".
 *
 * Callers sequence the critical path (flushed save completes, then sync starts — gate-5 finding 6);
 * this class only moves bytes.
 */
internal class WorldSync
internal constructor(
    /** Builds the platform clone argv, or null when the platform has no clone tier (Windows). */
    private val cloneArgv: ((src: File, dst: File) -> List<String>)? = platformCloneArgv(),
    /** Runs one clone command, true on success. Injectable so tests never exec processes. */
    private val runCommand: (List<String>) -> Boolean = ::runProcess,
) {
  /** How the last [sync] call moved the bytes, for tests and dogfood verification. */
  enum class Strategy {
    CLONE,
    INCREMENTAL,
  }

  @Volatile
  var lastStrategy: Strategy? = null
    private set

  // Latched on the first failed clone exec: the failure mode is "this filesystem can't reflink"
  // (confirmed on ext4, gate 5), which no retry heals — so stop paying a process exec per sync.
  // A transient failure also latches, costing only the fast path, never correctness.
  @Volatile private var cloneUnsupported = false

  /**
   * Syncs the world directory [src] to [dst]; [skipName] names entries (at any depth) that must not
   * survive in the target — lock files for world directories.
   */
  fun sync(src: File, dst: File, skipName: (String) -> Boolean) {
    if (cloneSync(src, dst, skipName)) {
      lastStrategy = Strategy.CLONE
      return
    }
    lastStrategy = Strategy.INCREMENTAL
    IncrementalSync.syncDir(src, dst, skipName)
  }

  private fun cloneSync(src: File, dst: File, skipName: (String) -> Boolean): Boolean {
    val argv = cloneArgv
    if (argv == null || cloneUnsupported) return false
    val parent = dst.absoluteFile.parentFile ?: return false
    parent.mkdirs()
    val tmp = File(parent, dst.name + CLONE_TMP_SUFFIX)
    IncrementalSync.deleteDir(tmp) // leftover from a crashed run
    val swapped =
        if (!runCommand(argv(src.absoluteFile, tmp))) {
          cloneUnsupported = true
          false
        } else {
          IncrementalSync.deleteDir(dst)
          // A rename failure is environmental (a scanner holding the dir, a half-deleted target),
          // not filesystem capability — cloneUnsupported stays unlatched and the fallback
          // reconciles the target.
          tmp.renameTo(dst)
        }
    if (!swapped) {
      IncrementalSync.deleteDir(tmp)
      return false
    }
    prune(dst, skipName)
    return true
  }

  /** Removes entries matching [skipName] at every depth of the cloned tree. */
  private fun prune(dir: File, skipName: (String) -> Boolean) {
    for (child in dir.listFiles() ?: return) {
      when {
        skipName(child.name) ->
            if (child.isDirectory) IncrementalSync.deleteDir(child) else child.delete()
        child.isDirectory -> prune(child, skipName)
      }
    }
  }

  companion object {
    /** Temp sibling suffix a clone lands under before being swapped into place. */
    internal const val CLONE_TMP_SUFFIX = ".ppl-clone-tmp"

    /**
     * The clone command for [osName], or null where cloning has no supported tier. `cp -c` is the
     * accepted clonefile vehicle per the gate-5 results doc (no JDK API exposes clonefile/reflink;
     * a process exec beats bundling native code).
     */
    internal fun platformCloneArgv(
        osName: String = System.getProperty("os.name")
    ): ((File, File) -> List<String>)? {
      val os = osName.lowercase()
      return when {
        os.contains("mac") -> { s, d ->
          listOf("cp", "-c", "-R", s.path, d.path)
        }
        os.contains("linux") -> { s, d ->
          listOf("cp", "-a", "--reflink=always", s.path, d.path)
        }
        else -> null
      }
    }

    private fun runProcess(argv: List<String>): Boolean {
      val process =
          try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
          } catch (_: IOException) {
            return false
          }
      return try {
        process.inputStream.readAllBytes() // drain so a chatty cp can't block on a full pipe
        process.waitFor() == 0
      } catch (_: IOException) {
        process.destroyForcibly()
        false
      } catch (_: InterruptedException) {
        process.destroyForcibly()
        Thread.currentThread().interrupt()
        false
      }
    }
  }
}

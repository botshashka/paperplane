package dev.paperplane.cli.server

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

/**
 * Generic timestamp+size incremental directory sync — the shared machinery under [ServerSync]
 * (whole-server-dir sync between blue-green slots) and [WorldSync] (the copy-on-write world
 * primitive's fallback tier). Pure file I/O, no policy: callers own the skip predicates and the
 * per-directory routing.
 */
internal object IncrementalSync {

  /** The skip predicate used below the server-dir top level: lock files never travel. */
  val SKIP_LOCK_FILES: (String) -> Boolean = { it.endsWith(".lock") }

  /**
   * Recursively syncs [src] into [dst], applying [skipName] at every level: orphans in [dst] are
   * removed, changed files copied, unchanged files (same size + mtime) left alone.
   */
  fun syncDir(src: File, dst: File, skipName: (String) -> Boolean = SKIP_LOCK_FILES) {
    syncChildren(src, dst, skipName, onDirectory = { s, d -> syncDir(s, d, skipName) })
  }

  /**
   * Two-pass directory sync: removes orphans in [dst] that aren't in [src] (skipping any name
   * matching [skipName]), then walks [src] and either recurses on directories ([onDirectory]) or
   * copies files ([onFile]). Skip predicate is applied to both passes.
   */
  fun syncChildren(
      src: File,
      dst: File,
      skipName: (String) -> Boolean,
      onFile: (File, File) -> Unit = ::copyIfChanged,
      onDirectory: (File, File) -> Unit = { s, d -> syncDir(s, d) },
  ) {
    dst.mkdirs()
    val srcChildren = (src.listFiles() ?: emptyArray()).associateBy { it.name }
    val dstChildren = (dst.listFiles() ?: emptyArray()).associateBy { it.name }

    for ((name, dstChild) in dstChildren) {
      if (skipName(name)) continue
      if (name !in srcChildren) {
        if (dstChild.isDirectory) deleteDir(dstChild) else dstChild.delete()
      }
    }

    for ((name, srcChild) in srcChildren) {
      if (skipName(name)) continue
      val dstChild = File(dst, name)
      if (srcChild.isDirectory) onDirectory(srcChild, dstChild) else onFile(srcChild, dstChild)
    }
  }

  private fun copyIfChanged(src: File, dst: File) {
    if (!dst.exists() || isFileChanged(src, dst)) {
      Files.copy(
          src.toPath(),
          dst.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.COPY_ATTRIBUTES,
      )
    }
  }

  private fun isFileChanged(src: File, dst: File): Boolean {
    if (src.length() != dst.length()) return true
    // Use NIO FileTime for higher resolution than File.lastModified() (millis)
    val srcTime: FileTime = Files.getLastModifiedTime(src.toPath())
    val dstTime: FileTime = Files.getLastModifiedTime(dst.toPath())
    return srcTime != dstTime
  }

  fun deleteDir(dir: File) {
    if (!dir.exists()) return
    val files = dir.listFiles()
    if (files != null) {
      for (child in files) {
        if (child.isDirectory) deleteDir(child) else child.delete()
      }
    }
    dir.delete()
  }
}

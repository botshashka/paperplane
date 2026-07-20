package dev.paperplane.cli.server

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

object ServerSync {

  /**
   * Syncs runtime state (world data, player data, plugin data directories, etc) from source to
   * target. Uses incremental sync (timestamp + size) to skip unchanged files. Skips lock files, CLI
   * state, the dev plugin jar, the companion jar, and all PaperPlane-managed config files — those
   * come from `paperplane.yml` directly via configure().
   */
  fun syncServerState(sourceDir: File, targetDir: File, devPluginJarName: String) {
    syncChildren(
        sourceDir,
        targetDir,
        skipName = {
          it.endsWith(".lock") || it == ".paperplane" || it in ServerConfigs.MANAGED_CONFIG_FILES
        },
        onDirectory = { s, d ->
          if (s.name == "plugins") syncPlugins(s, d, devPluginJarName) else incrementalSyncDir(s, d)
        },
    )
  }

  private fun syncPlugins(srcPlugins: File, dstPlugins: File, devPluginJarName: String) {
    syncChildren(
        srcPlugins,
        dstPlugins,
        skipName = { it == devPluginJarName || it == "paperplane-companion.jar" },
    )
  }

  private fun incrementalSyncDir(src: File, dst: File) {
    syncChildren(src, dst, skipName = { it.endsWith(".lock") })
  }

  /**
   * Two-pass directory sync: removes orphans in [dst] that aren't in [src] (skipping any name
   * matching [skipName]), then walks [src] and either recurses on directories ([onDirectory]) or
   * copies files ([onFile]). Skip predicate is applied to both passes.
   */
  private fun syncChildren(
      src: File,
      dst: File,
      skipName: (String) -> Boolean,
      onFile: (File, File) -> Unit = ::copyIfChanged,
      onDirectory: (File, File) -> Unit = ::incrementalSyncDir,
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

  private fun deleteDir(dir: File) {
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

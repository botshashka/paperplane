package dev.paperplane.cli.server

import java.io.File

object ServerSync {

  // One process-wide instance so the clone-unsupported latch is learned once, not per sync — every
  // synced directory in a session lives under the same .paperplane volume.
  private val defaultWorldSync = WorldSync()

  /**
   * Syncs runtime state (world data, player data, plugin data directories, etc) from source to
   * target. World directories (identified by `level.dat`) go through [WorldSync] — copy-on-write
   * clone where the filesystem supports it, incremental copy otherwise; everything else uses
   * incremental sync (timestamp + size) to skip unchanged files. Skips lock files, CLI state, the
   * dev plugin jar, the companion jar, and all PaperPlane-managed config files — those come from
   * `paperplane.yml` directly via configure().
   */
  fun syncServerState(sourceDir: File, targetDir: File, devPluginJarName: String) {
    syncServerState(sourceDir, targetDir, devPluginJarName, defaultWorldSync)
  }

  /** [syncServerState] with an injectable [worldSync], the seam tests use to observe routing. */
  internal fun syncServerState(
      sourceDir: File,
      targetDir: File,
      devPluginJarName: String,
      worldSync: WorldSync,
  ) {
    IncrementalSync.syncChildren(
        sourceDir,
        targetDir,
        skipName = {
          it.endsWith(".lock") || it == ".paperplane" || it in ServerConfigs.MANAGED_CONFIG_FILES
        },
        onDirectory = { s, d ->
          when {
            s.name == "plugins" -> syncPlugins(s, d, devPluginJarName)
            isWorldDir(s) -> worldSync.sync(s, d, IncrementalSync.SKIP_LOCK_FILES)
            else -> IncrementalSync.syncDir(s, d)
          }
        },
    )
  }

  private fun syncPlugins(srcPlugins: File, dstPlugins: File, devPluginJarName: String) {
    IncrementalSync.syncChildren(
        srcPlugins,
        dstPlugins,
        skipName = { it == devPluginJarName || it == "paperplane-companion.jar" },
    )
  }

  private fun isWorldDir(dir: File): Boolean = File(dir, "level.dat").isFile
}

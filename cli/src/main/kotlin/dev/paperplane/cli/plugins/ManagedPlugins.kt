package dev.paperplane.cli.plugins

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Tracks which plugin JARs in `server/plugins/` are managed by the PaperPlane plugin manager. The
 * manifest (a JSON array of filenames) lives at
 * `.paperplane/<serverDir>/.paperplane/managed-plugins.json` — the same internal-state directory
 * that holds `companion-status.json` and `server-ready`.
 *
 * This solves the "mixed-ownership directory" problem: `server/plugins/` contains the dev plugin
 * jar, the companion jar, plugin data directories, AND dependency jars. Without a manifest, pruning
 * (deleting jars for removed dependencies) would need to know every non-managed file by name. The
 * manifest flips the model: we track what WE placed, and only ever touch our own files.
 *
 * Also provides [copyJars] — the atomic-copy loop extracted from `PaperServerManager` so both `ppl
 * dev` and `ppl plugin install` can deploy jars without constructing a full server manager.
 */
object ManagedPlugins {
  private const val FILENAME = "managed-plugins.json"
  private val gson = Gson()

  /** Reads the manifest. Returns an empty set if the file doesn't exist or is malformed. */
  fun load(serverDir: File): Set<String> {
    val file = manifestFile(serverDir)
    if (!file.exists()) return emptySet()
    return try {
      val type = object : TypeToken<List<String>>() {}.type
      val list: List<String> = gson.fromJson(file.readText(), type)
      list.toSet()
    } catch (_: Exception) {
      emptySet()
    }
  }

  /** Atomically writes the manifest. Creates parent dirs if needed. */
  fun save(serverDir: File, filenames: Set<String>) {
    val dir = File(serverDir, ".paperplane").apply { mkdirs() }
    val file = File(dir, FILENAME)
    val tmp = File(dir, ".$FILENAME.tmp")
    tmp.writeText(gson.toJson(filenames.sorted()))
    atomicMoveOrFallback(tmp.toPath(), file.toPath())
  }

  /**
   * Full prune cycle: loads the old manifest, deletes orphan jars from `plugins/`, and saves
   * [currentFilenames] as the new manifest. Files not in the old manifest are never touched — the
   * dev plugin jar, companion jar, and plugin data directories are all safe.
   */
  fun prune(serverDir: File, currentFilenames: Set<String>): Set<String> {
    val oldManifest = load(serverDir)
    val orphans = oldManifest - currentFilenames
    if (orphans.isNotEmpty()) {
      val pluginsDir = File(serverDir, "plugins")
      for (orphan in orphans) {
        File(pluginsDir, orphan).delete() // idempotent — no exists() pre-check needed
      }
    }
    save(serverDir, currentFilenames)
    return orphans
  }

  /**
   * Atomically copies each JAR into [targetDir] using the `.tmp` + `ATOMIC_MOVE` pattern. Skips
   * files that already exist with matching size and mtime. Shared between
   * `PaperServerManager.copyDependencyPlugins` and `InstallPluginCommand`.
   */
  fun copyJars(jars: List<File>, targetDir: File) {
    targetDir.mkdirs()
    for (jar in jars) {
      val target = File(targetDir, jar.name)
      if (
          target.exists() &&
              target.length() == jar.length() &&
              target.lastModified() == jar.lastModified()
      ) {
        continue
      }
      val temp = File(targetDir, ".${jar.name}.tmp")
      jar.copyTo(temp, overwrite = true)
      atomicMoveOrFallback(temp.toPath(), target.toPath())
    }
  }

  private fun manifestFile(serverDir: File) = File(serverDir, ".paperplane/$FILENAME")
}

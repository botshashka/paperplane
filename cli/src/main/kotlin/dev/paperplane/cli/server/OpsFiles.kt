package dev.paperplane.cli.server

import com.google.gson.Gson
import java.io.File
import java.util.UUID

/**
 * The op-list files in a server directory: vanilla's `ops.json` and PaperPlane's own
 * `.paperplane/op-banlist.json`.
 *
 * Split out of [PaperServerManager] because none of it touches the server process or the companion
 * connection — it is pure file marshalling that happens to run during `configure`, and it is the
 * one cluster in there with a clean seam.
 */
internal object OpsFiles {

  /** Highest vanilla op permission level — full command access on the dev server. */
  private const val OP_PERMISSION_LEVEL = 4

  private val gson = Gson()

  /**
   * Writes `ops.json` if [names] is non-empty. Uses offline-mode UUIDs (deterministic from name)
   * since the dev server runs with `online-mode=false`. PaperPlane's companion plugin also auto-ops
   * joining players at runtime — this list seeds known ops across fresh server directories.
   */
  fun writeOps(serverDir: File, names: List<String>) {
    val opsFile = File(serverDir, "ops.json")
    if (names.isEmpty()) {
      opsFile.delete() // idempotent — no exists() pre-check
      return
    }
    val entries = names.map { name ->
      mapOf(
          "uuid" to offlineUuid(name).toString(),
          "name" to name,
          "level" to OP_PERMISSION_LEVEL,
          "bypassesPlayerLimit" to false,
      )
    }
    opsFile.writeText(gson.toJson(entries))
  }

  /**
   * Writes the op banlist to `.paperplane/op-banlist.json` as a JSON array of names. The companion
   * plugin reads this file on join events and skips auto-opping any listed name. Also consulted by
   * the CLI's reverse-sync to keep banned names out of `paperplane.yml`.
   */
  fun writeBanlist(serverDir: File, names: List<String>) {
    val statusDir = File(serverDir, ".paperplane").apply { mkdirs() }
    val file = File(statusDir, "op-banlist.json")
    if (names.isEmpty()) {
      file.delete()
      return
    }
    file.writeText(gson.toJson(names))
  }

  /**
   * Reads current op names from `ops.json`. Empty when the file is missing or malformed. Used for
   * reverse-sync of auto-opped players back into `paperplane.yml`.
   */
  fun readOpNames(serverDir: File): List<String> =
      try {
        @Suppress("UNCHECKED_CAST")
        val arr =
            gson.fromJson(File(serverDir, "ops.json").readText(), List::class.java)
                as? List<Map<String, Any>>
        arr?.mapNotNull { it["name"] as? String } ?: emptyList()
      } catch (_: Exception) {
        // ops.json missing, unreadable, or malformed — treat as no ops.
        emptyList()
      }

  /** Deterministic UUID that Minecraft uses for offline-mode players. */
  private fun offlineUuid(name: String): UUID =
      UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
}

package dev.paperplane.cli.devserver

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Request from CLI to companion: "load this plugin." Written to `.paperplane/load-request.json`,
 * picked up by the companion's `BuildStatusBar` poll loop, and dispatched to `InnerPluginHost`.
 *
 * The companion picks the reload strategy from the contents:
 * - `changedClasses` non-empty AND no structural changes → HOTSWAP via Instrumentation.
 * - `classesDirs` non-empty → DIRECTORY reload (Level 1).
 * - Otherwise → JAR reload (Level 0).
 *
 * Strategy was previously sent via `companion-status.json reloadStrategy=...`; that channel is gone.
 */
data class LoadRequest(
    val requestId: String,
    val jarPath: String,
    val pluginName: String,
    val classesDirs: List<String> = emptyList(),
    val resourcesDir: String = "",
    val runtimeClasspath: List<String> = emptyList(),
    val changedClasses: List<String> = emptyList(),
) {
  companion object {
    private val gson = Gson()
    private const val FILE_NAME = "load-request.json"
    private const val TMP_NAME = ".load-request.tmp"

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * Atomic write to `.paperplane/load-request.json`. Writes to a tmp file first, then renames —
     * the companion's polling loop never sees a partial file.
     */
    fun write(serverDir: File, request: LoadRequest) {
      val ppDir = File(serverDir, ".paperplane").apply { mkdirs() }
      val target = File(ppDir, FILE_NAME)
      val tmp = File(ppDir, TMP_NAME)
      tmp.writeText(gson.toJson(request))
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun requestPath(serverDir: File): File = File(serverDir, ".paperplane/$FILE_NAME")

    fun completeFlag(serverDir: File): File = File(serverDir, ".paperplane/load-complete")

    fun failedFlag(serverDir: File): File = File(serverDir, ".paperplane/load-failed")
  }
}

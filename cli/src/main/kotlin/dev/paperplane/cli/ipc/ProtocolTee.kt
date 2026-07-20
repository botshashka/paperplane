package dev.paperplane.cli.ipc

import com.google.gson.JsonObject
import java.io.File
import java.io.IOException

/**
 * Debug tee for the socket protocol: appends every message crossing the connection — both
 * directions, including the handshake — to `.paperplane/protocol-log.ndjson` as one JSON object per
 * line: `{"at": <epoch ms>, "dir": "send"|"recv", "line": <raw message line>}`.
 *
 * The raw line is stored verbatim (as a string, not re-parsed) so the log is a byte-faithful
 * forensic record and can be replayed through [CompanionWire.decode] in protocol tests. Enabled via
 * `dev.protocol-log: true` in `paperplane.yml`. Best-effort: a failed append never disturbs the
 * live protocol.
 */
internal class ProtocolTee(private val file: File) {
  companion object {
    const val SEND = "send"
    const val RECV = "recv"
    private const val FILE_NAME = "protocol-log.ndjson"

    fun forServer(serverDir: File): ProtocolTee =
        ProtocolTee(File(serverDir, ".paperplane/$FILE_NAME"))
  }

  @Synchronized
  fun record(direction: String, rawLine: String) {
    try {
      file.parentFile?.mkdirs()
      val entry =
          JsonObject().apply {
            addProperty("at", System.currentTimeMillis())
            addProperty("dir", direction)
            addProperty("line", rawLine)
          }
      file.appendText(entry.toString() + "\n")
    } catch (_: IOException) {
      // Forensics must never break the protocol.
    }
  }
}

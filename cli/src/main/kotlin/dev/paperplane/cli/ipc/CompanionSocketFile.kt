package dev.paperplane.cli.ipc

import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.File
import java.io.IOException

/**
 * Discovery handshake for the CLI↔companion socket. The companion binds a localhost TCP socket on
 * an ephemeral port and writes this file to `.paperplane/`; the CLI polls for it, dials the port,
 * and authenticates with [token]. The file is the ONLY file in the protocol — everything after
 * discovery flows over the socket.
 *
 * [token] guards against two failure shapes: another local process squatting the port of a stale
 * handshake file, and cross-talk between unrelated PaperPlane sessions. The CLI must echo it in its
 * `hello`; the companion drops non-matching connections.
 */
data class CompanionSocketInfo(
    val port: Int = 0,
    val token: String = "",
    val protocolVersion: Int = 0,
)

object CompanionSocketFile {
  /**
   * Version of the socket message schema. Bumped on any wire-shape change; the hello/welcome
   * handshake carries it so a stale companion jar can't silently misparse. (Versions 1–2 were the
   * retired flag-file protocol's `companion-config.json`/`companion-status.json` markers; version
   * 3 predates the instant tier — v4 adds `instantSwap`/`instantReport`, welcome capabilities, and
   * removes the load request's `changedClasses`.)
   */
  const val PROTOCOL_VERSION = 4

  private const val FILE_NAME = "companion-socket.json"
  private const val MAX_PORT = 65535
  private val gson = Gson()

  fun path(serverDir: File): File = File(serverDir, ".paperplane/$FILE_NAME")

  /**
   * Reads and validates the handshake file. Returns null when the file is missing, torn (the
   * companion writes it in one small write, but a mid-write read is still possible), or fails
   * validation — the CLI's dial loop just retries on its next poll.
   */
  fun read(serverDir: File): CompanionSocketInfo? {
    val file = path(serverDir)
    return try {
      if (!file.isFile) null
      else
          gson.fromJson(file.readText(), CompanionSocketInfo::class.java)?.takeIf {
            it.port in 1..MAX_PORT && it.token.isNotEmpty()
          }
    } catch (_: IOException) {
      null
    } catch (_: JsonParseException) {
      null
    }
  }

  /**
   * Deletes a leftover handshake file. Called before the server process launches so the dial loop
   * can never connect to a previous run's (possibly reassigned) port.
   */
  fun delete(serverDir: File) {
    path(serverDir).delete()
  }
}

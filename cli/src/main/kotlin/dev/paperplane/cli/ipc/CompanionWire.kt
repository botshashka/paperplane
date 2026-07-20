package dev.paperplane.cli.ipc

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import dev.paperplane.cli.devserver.LoadReport
import dev.paperplane.cli.devserver.LoadRequest

/**
 * Wire codec for the CLI↔companion socket: NDJSON — one JSON object per line, UTF-8, discriminated
 * by a `type` field. Mirror of the companion's codec; the modules share no code, so any shape
 * change here must land there too (and bump [CompanionSocketFile.PROTOCOL_VERSION]).
 *
 * CLI → companion: `hello` (auth handshake), `status` (build-state broadcast + save trigger),
 * `load` ([LoadRequest] + type tag).
 *
 * Companion → CLI ([CompanionEvent]): `welcome` (handshake reply + server-state snapshot), `ready`
 * (explicit server-readiness event — an established connection proves the COMPANION is alive, not
 * that the server is player-ready, so readiness is always a streamed event and never inferred from
 * the connection), `saveComplete`, `loadProgress` (streamed load stages), `report` ([LoadReport]).
 */
internal object CompanionWire {
  const val STATE_SAVING = "saving"
  const val STATE_BUILDING = "building"
  const val STATE_READY = "ready"
  const val STATE_ERROR = "error"

  private val gson = Gson()

  fun encodeHello(token: String): String =
      JsonObject()
          .apply {
            addProperty("type", "hello")
            addProperty("token", token)
            addProperty("protocolVersion", CompanionSocketFile.PROTOCOL_VERSION)
          }
          .toString()

  fun encodeStatus(state: String, duration: String?, message: String?): String =
      JsonObject()
          .apply {
            addProperty("type", "status")
            addProperty("state", state)
            duration?.let { addProperty("duration", it) }
            message?.let { addProperty("message", it) }
          }
          .toString()

  /** Encodes a [LoadRequest] as its plain JSON object plus the `load` type tag. */
  fun encodeLoad(request: LoadRequest): String =
      gson.toJsonTree(request).asJsonObject.apply { addProperty("type", "load") }.toString()

  /**
   * Decodes one companion→CLI line. Returns null for unparseable lines and unknown types — the
   * reader logs-and-skips rather than dying, so a newer companion emitting an event this CLI
   * doesn't know can't break the session.
   */
  fun decode(line: String): CompanionEvent? {
    val obj =
        try {
          gson.fromJson(line, JsonObject::class.java) ?: return null
        } catch (_: JsonParseException) {
          return null
        }
    return when (obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString) {
      "welcome" ->
          CompanionEvent.Welcome(
              protocolVersion =
                  obj.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
              serverReady =
                  obj.get("serverReady")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
          )
      "ready" -> CompanionEvent.Ready
      "saveComplete" -> CompanionEvent.SaveComplete
      "loadProgress" ->
          CompanionEvent.LoadProgress(
              requestId = obj.get("requestId")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
              stage = obj.get("stage")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
          )
      "report" ->
          try {
            CompanionEvent.Report(gson.fromJson(obj, LoadReport::class.java))
          } catch (_: JsonParseException) {
            null
          }
      else -> null
    }
  }
}

/** One decoded companion→CLI message. See [CompanionWire] for the full wire contract. */
internal sealed interface CompanionEvent {
  /**
   * Handshake reply. [serverReady] snapshots whether `ServerLoadEvent` already fired — the CLI may
   * be reconnecting to a long-running server.
   */
  data class Welcome(val protocolVersion: Int, val serverReady: Boolean) : CompanionEvent

  /** The server finished full startup (`ServerLoadEvent`). */
  object Ready : CompanionEvent

  /** The companion finished the world save requested by a `saving` status. */
  object SaveComplete : CompanionEvent

  /** Streamed load stage (`loading` today; Fresh mode will add more). */
  data class LoadProgress(val requestId: String, val stage: String) : CompanionEvent

  /** Terminal answer to a `load` request. */
  data class Report(val report: LoadReport) : CompanionEvent
}

package dev.paperplane.cli.ipc

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import dev.paperplane.cli.devserver.InstantSwapReport
import dev.paperplane.cli.devserver.InstantSwapRequest
import dev.paperplane.cli.devserver.LoadReport
import dev.paperplane.cli.devserver.LoadRequest

/**
 * Wire codec for the CLI↔companion socket: NDJSON — one JSON object per line, UTF-8, discriminated
 * by a `type` field. Mirror of the companion's codec; the modules share no code, so any shape
 * change here must land there too (and bump [CompanionSocketFile.PROTOCOL_VERSION]).
 *
 * CLI → companion: `hello` (auth handshake), `status` (build-state broadcast + save trigger),
 * `load` ([LoadRequest] + type tag), `instantSwap` ([InstantSwapRequest] — in-place patch).
 *
 * Companion → CLI ([CompanionEvent]): `welcome` (handshake reply + server-state snapshot + the
 * JVM's redefine capabilities), `ready` (explicit server-readiness event — an established
 * connection proves the COMPANION is alive, not that the server is player-ready, so readiness is
 * always a streamed event and never inferred from the connection), `saveComplete`, `loadProgress`
 * (streamed load stages), `report` ([LoadReport]), `instantReport` ([InstantSwapReport]).
 */
internal object CompanionWire {
  const val STATE_SAVING = "saving"
  const val STATE_BUILDING = "building"
  const val STATE_READY = "ready"
  const val STATE_ERROR = "error"

  // Wire message `type` discriminators. Mirror of the companion's CompanionSocketServer tags; the
  // modules share no code, so a tag introduced on one side must be added on the other.
  private const val TYPE_HELLO = "hello"
  private const val TYPE_STATUS = "status"
  private const val TYPE_LOAD = "load"
  private const val TYPE_INSTANT_SWAP = "instantSwap"
  private const val TYPE_WELCOME = "welcome"
  private const val TYPE_READY = "ready"
  private const val TYPE_SAVE_COMPLETE = "saveComplete"
  private const val TYPE_LOAD_PROGRESS = "loadProgress"
  private const val TYPE_REPORT = "report"
  private const val TYPE_INSTANT_REPORT = "instantReport"

  private val gson = Gson()

  fun encodeHello(token: String): String =
      JsonObject()
          .apply {
            addProperty("type", TYPE_HELLO)
            addProperty("token", token)
            addProperty("protocolVersion", CompanionSocketFile.PROTOCOL_VERSION)
          }
          .toString()

  fun encodeStatus(state: String, duration: String?, message: String?): String =
      JsonObject()
          .apply {
            addProperty("type", TYPE_STATUS)
            addProperty("state", state)
            duration?.let { addProperty("duration", it) }
            message?.let { addProperty("message", it) }
          }
          .toString()

  /** Encodes a [LoadRequest] as its plain JSON object plus the `load` type tag. */
  fun encodeLoad(request: LoadRequest): String =
      gson.toJsonTree(request).asJsonObject.apply { addProperty("type", TYPE_LOAD) }.toString()

  /** Encodes an [InstantSwapRequest] as its plain JSON object plus the `instantSwap` type tag. */
  fun encodeInstantSwap(request: InstantSwapRequest): String =
      gson
          .toJsonTree(request)
          .asJsonObject
          .apply { addProperty("type", TYPE_INSTANT_SWAP) }
          .toString()

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
      TYPE_WELCOME -> {
        val capabilities = obj.get("capabilities")?.takeIf { it.isJsonObject }?.asJsonObject
        CompanionEvent.Welcome(
            protocolVersion = obj.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
            serverReady = obj.get("serverReady")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            agent =
                capabilities?.get("agent")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            enhanced =
                capabilities?.get("enhanced")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
        )
      }
      TYPE_READY -> CompanionEvent.Ready
      TYPE_SAVE_COMPLETE -> CompanionEvent.SaveComplete
      TYPE_LOAD_PROGRESS ->
          CompanionEvent.LoadProgress(
              requestId = obj.get("requestId")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
              stage = obj.get("stage")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
          )
      TYPE_REPORT ->
          try {
            CompanionEvent.Report(gson.fromJson(obj, LoadReport::class.java))
          } catch (_: JsonParseException) {
            null
          }
      TYPE_INSTANT_REPORT ->
          try {
            CompanionEvent.InstantReport(gson.fromJson(obj, InstantSwapReport::class.java))
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
   * be reconnecting to a long-running server. [agent]/[enhanced] are the live JVM's redefine
   * capabilities (instrumentation agent present; JBR enhanced redefinition actually armed) — a
   * property of the running server process, so per-connection is exactly per-JVM.
   */
  data class Welcome(
      val protocolVersion: Int,
      val serverReady: Boolean,
      val agent: Boolean = false,
      val enhanced: Boolean = false,
  ) : CompanionEvent

  /** The server finished full startup (`ServerLoadEvent`). */
  object Ready : CompanionEvent

  /** The companion finished the world save requested by a `saving` status. */
  object SaveComplete : CompanionEvent

  /** Streamed load stage (`loading` today; Fresh mode will add more). */
  data class LoadProgress(val requestId: String, val stage: String) : CompanionEvent

  /** Terminal answer to a `load` request. */
  data class Report(val report: LoadReport) : CompanionEvent

  /** Terminal answer to an `instantSwap` request. */
  data class InstantReport(val report: InstantSwapReport) : CompanionEvent
}

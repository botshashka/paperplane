package dev.paperplane.cli.ipc

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import dev.paperplane.cli.devserver.InstantSwapReport
import dev.paperplane.cli.devserver.InstantSwapRequest
import dev.paperplane.cli.devserver.LoadReport
import dev.paperplane.cli.devserver.LoadRequest
import dev.paperplane.cli.devserver.instant.RedefineCapability

/**
 * Wire codec for the CLI↔companion socket: NDJSON — one JSON object per line, UTF-8, discriminated
 * by a `type` field. Mirror of the companion's codec; the modules share no code, so any shape
 * change here must land there too (and bump [CompanionSocketFile.PROTOCOL_VERSION]).
 *
 * CLI → companion: `hello` (auth handshake), `status` (build-state broadcast + save trigger),
 * `load` ([LoadRequest] + type tag), `instantSwap` ([InstantSwapRequest] — in-place patch).
 *
 * Companion → CLI ([CompanionEvent]): `welcome` (handshake reply + server-state snapshot + the
 * JVM's redefine capability), `ready` (explicit server-readiness event — an established connection
 * proves the COMPANION is alive, not that the server is player-ready, so readiness is always a
 * streamed event and never inferred from the connection), `saveComplete`, `loadProgress` (streamed
 * load stages), `report` ([LoadReport]), `instantReport` ([InstantSwapReport]).
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
  fun encodeLoad(request: LoadRequest): String = tagged(request, TYPE_LOAD)

  /** Encodes an [InstantSwapRequest] as its plain JSON object plus the `instantSwap` type tag. */
  fun encodeInstantSwap(request: InstantSwapRequest): String = tagged(request, TYPE_INSTANT_SWAP)

  /** A request object serialized as its plain JSON shape plus the wire's `type` discriminator. */
  private fun tagged(payload: Any, type: String): String =
      gson.toJsonTree(payload).asJsonObject.apply { addProperty("type", type) }.toString()

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
    return when (obj.str("type")) {
      TYPE_WELCOME ->
          CompanionEvent.Welcome(
              protocolVersion = obj.int("protocolVersion"),
              serverReady = obj.bool("serverReady"),
              capability = obj.capability("capability"),
          )
      TYPE_READY -> CompanionEvent.Ready
      TYPE_SAVE_COMPLETE -> CompanionEvent.SaveComplete
      TYPE_LOAD_PROGRESS ->
          CompanionEvent.LoadProgress(
              requestId = obj.str("requestId") ?: "",
              stage = obj.str("stage") ?: "",
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

  // Typed accessors over a possibly-absent, possibly-wrong-typed field. A companion one version
  // ahead or behind must degrade to the default rather than throw — the reader logs and skips, so
  // an exception here would take down a session over a field it doesn't even use.
  private fun JsonObject.str(name: String): String? =
      get(name)?.takeIf { it.isJsonPrimitive }?.asString

  private fun JsonObject.int(name: String, default: Int = 0): Int =
      get(name)?.takeIf { it.isJsonPrimitive }?.asInt ?: default

  private fun JsonObject.bool(name: String, default: Boolean = false): Boolean =
      get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: default

  /**
   * The redefine capability enum, or [RedefineCapability.NONE] for an absent/unknown value — a
   * companion advertising a tier this CLI doesn't know degrades to "can't patch", never to a tier
   * it can't honour.
   */
  private fun JsonObject.capability(name: String): RedefineCapability =
      get(name)
          ?.takeIf { it.isJsonPrimitive }
          ?.let { runCatching { gson.fromJson(it, RedefineCapability::class.java) }.getOrNull() }
          ?: RedefineCapability.NONE
}

/** One decoded companion→CLI message. See [CompanionWire] for the full wire contract. */
internal sealed interface CompanionEvent {
  /**
   * Handshake reply. [serverReady] snapshots whether `ServerLoadEvent` already fired — the CLI may
   * be reconnecting to a long-running server. [capability] is the live JVM's redefine tier — a
   * property of the running server process, so per-connection is exactly per-JVM.
   */
  data class Welcome(
      val protocolVersion: Int,
      val serverReady: Boolean,
      val capability: RedefineCapability = RedefineCapability.NONE,
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

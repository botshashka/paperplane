package dev.paperplane.companion.host

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

/**
 * Controls how much classloader-leak diagnostics the host emits. Mirror of the CLI's
 * `LeakDiagnosticsMode`, transported as a lowercase string in `.paperplane/companion-config.json`.
 *
 * The mode gates diagnostic **output only** — leak counting, the cheap attribution scan that rides
 * out on the load report, and the leak-limit auto-restart action stay active in every mode:
 * - [OFF] — no diagnostic output at all: no one-line leak log, no verbose per-loader dump, no heap
 *   dump. Counting and the auto-restart action still run, so `off` can't silently restore the old
 *   do-nothing dead-end.
 * - [SUMMARY] — the default: the leak attribution rides out on the load report (so the CLI can name
 *   the likely cause) and a single one-line warning is logged. No verbose dump, no heap dump.
 * - [FULL] — everything [SUMMARY] does, plus the verbose per-loader diagnostics and a one-shot heap
 *   dump to `.paperplane/leak.hprof`.
 */
enum class LeakDiagnosticsMode {
  OFF,
  SUMMARY,
  FULL;

  companion object {
    /**
     * Parses the wire value written by the CLI; anything unrecognized or null falls back to
     * [SUMMARY].
     */
    fun fromWire(value: String?): LeakDiagnosticsMode =
        when (value?.lowercase()) {
          "off" -> OFF
          "full" -> FULL
          else -> SUMMARY
        }

    /**
     * Reads the mode from the CLI-written `companion-config.json`. A missing file, malformed JSON,
     * or an unknown mode all fall back to [SUMMARY]. Never throws — a config hiccup must not be
     * able to take the companion down.
     */
    fun readFrom(configFile: File): LeakDiagnosticsMode =
        try {
          if (!configFile.isFile) {
            SUMMARY
          } else {
            val json = Gson().fromJson(configFile.readText(), JsonObject::class.java)
            fromWire(json?.get("leakDiagnostics")?.asString)
          }
        } catch (
            @Suppress("TooGenericExceptionCaught") // A diagnostics-config read must never throw.
            _: Exception) {
          // Missing/torn/unreadable config → the safe default. The mode is a convenience, not
          // load-bearing: never let it fail the companion.
          SUMMARY
        }
  }
}

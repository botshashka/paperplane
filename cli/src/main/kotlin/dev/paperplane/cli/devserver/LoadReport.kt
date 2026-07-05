package dev.paperplane.cli.devserver

import dev.paperplane.cli.ui.TerminalUI

/**
 * Mirror of the companion's `HostLoadReport` — same JSON shape, no cross-module dependency. Written
 * by the companion to `.paperplane/load-complete` (status `ok`) or `load-failed`, read by
 * [LoadResultWaiter]. All fields default so a torn or partial document deserializes without
 * throwing.
 *
 * `requestId` echoes the CLI's `LoadRequest.requestId` so the waiter can discard stale results from
 * a previous reload. `strategy` ∈ hotswap|fresh|reload. `leaks`/`action` are populated by the
 * host's leak-detection path and are absent on a clean load.
 */
data class LoadReport(
    val requestId: String = "",
    val status: String = "",
    val strategy: String = "",
    val durationMs: Long = 0,
    val pluginName: String = "",
    val message: String? = null,
    val leaks: LeakSummary? = null,
    val action: String? = null,
)

/**
 * One attributed source of a surviving classloader leak. `kind` ∈ thread|scheduler|command|unknown.
 */
data class LeakAttribution(val kind: String = "unknown", val detail: String = "")

/** Leak accounting mirrored from the companion's `LeakSummary`. */
data class LeakSummary(
    val consecutive: Int = 0,
    val confirmedSurvivors: Int = 0,
    val attribution: List<LeakAttribution> = emptyList(),
)

/**
 * Renders a concise leak warning when the host attributes a memory leak to the reload. No-op when
 * the report carries no attribution (the common case). One line, not a diagnostic dump — the full
 * diagnostics land server-side in the dev log.
 */
internal fun TerminalUI.renderLeakWarnings(report: LoadReport?) {
  val attribution = report?.leaks?.attribution ?: return
  if (attribution.isEmpty()) return
  val first = attribution.first()
  status("⚠ Reload leaked memory — likely cause: ${first.detail}")
}

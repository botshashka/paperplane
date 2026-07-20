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
) {
  companion object {
    /**
     * [action] value asking the CLI to restart the server (mirror of
     * `HostLoadReport.ACTION_RESTART`).
     */
    const val ACTION_RESTART = "restart"
  }
}

/**
 * One attributed source of a surviving classloader leak. The companion emits `thread` or
 * `scheduler`; the `unknown` default is only a deserialization fallback.
 */
data class LeakAttribution(val kind: String = "unknown", val detail: String = "")

/** Leak accounting mirrored from the companion's `LeakSummary`. */
data class LeakSummary(
    val consecutive: Int = 0,
    val confirmedSurvivors: Int = 0,
    val attribution: List<LeakAttribution> = emptyList(),
)

/**
 * Maps an initial-load failure to one short, actionable hint, rendered dimly under the host's
 * message by [DevSession.startServerAndReport] so a load failure diagnoses its likely cause instead
 * of just stating it. Returns null only for [LoadWaitResult.Ok].
 *
 * The categories mirror [LoadWaitResult]'s failure shapes:
 * - [LoadWaitResult.Failed] whose message mentions `plugin.yml` — PaperPlane generates plugin.yml
 *   from the `paperplane { }` block, so that's where the user looks (missing/typo'd block).
 *   `paper-plugin.yml` messages are excluded: that's the host telling the user to switch modes, and
 *   pointing at the paperplane block would be wrong advice.
 * - Any other [LoadWaitResult.Failed] (probe failure, NMS use, an onEnable exception surfaced as
 *   the host message) — the detail is in the server log printed above.
 * - [LoadWaitResult.TimedOut] — the host never answered; the server log is the place to look.
 * - [LoadWaitResult.ServerExited] — the JVM died mid-load, which almost always means a crash in the
 *   plugin's static initializer or onEnable.
 */
internal fun loadFailureHint(result: LoadWaitResult): String? =
    when (result) {
      is LoadWaitResult.Failed ->
          if (
              result.message.contains("plugin.yml", ignoreCase = true) &&
                  !result.message.contains("paper-plugin.yml", ignoreCase = true)
          )
              "PaperPlane generates plugin.yml from the paperplane { } block in build.gradle.kts — " +
                  "check that block and rebuild."
          else "See the server log above for the plugin's load error."
      LoadWaitResult.TimedOut ->
          "The plugin never reported back — check the server log above for errors during load."
      LoadWaitResult.ServerExited ->
          "The server crashed while loading — likely an exception in the plugin's static " +
              "initializer or onEnable. See the log above."
      is LoadWaitResult.Ok -> null
    }

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

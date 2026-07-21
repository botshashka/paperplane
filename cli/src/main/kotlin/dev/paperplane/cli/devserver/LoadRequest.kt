package dev.paperplane.cli.devserver

import java.util.UUID

/**
 * Request from CLI to companion: "load this plugin." Sent over the companion socket as a `load`
 * message (see `CompanionWire`), dispatched by the companion to `InnerPluginHost`.
 *
 * The companion picks the reload strategy from the contents:
 * - `changedClasses` non-empty AND no structural changes → HOTSWAP via Instrumentation.
 * - `classesDirs` non-empty → DIRECTORY reload (Level 1).
 * - Otherwise → JAR reload (Level 0).
 *
 * [leakDiagnostics] carries the host's leak-diagnostics mode (wire values of the CLI's
 * `LeakDiagnosticsMode`). The host is built once, on the first load request, so only the first
 * request's value takes effect.
 */
data class LoadRequest(
    val requestId: String,
    val jarPath: String,
    val pluginName: String,
    val classesDirs: List<String> = emptyList(),
    val resourcesDir: String = "",
    val runtimeClasspath: List<String> = emptyList(),
    val changedClasses: List<String> = emptyList(),
    val leakDiagnostics: String = "summary",
) {
  companion object {
    fun newId(): String = UUID.randomUUID().toString()
  }
}

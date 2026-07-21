package dev.paperplane.companion

import com.google.gson.annotations.SerializedName

/**
 * Mirror of the CLI's `InstantSwapRequest` — same JSON shape, no cross-module dependency. The CLI
 * asks for [classes] to be redefined in place (each verified against [HostInstantClassEntry
 * .expectedCrc32] before anything is touched) and [newClasses] to be made loadable. Applied
 * atomically by [InstantSwapper]; any check that fails refuses the whole request.
 */
data class HostInstantSwapRequest(
    val requestId: String = "",
    val pluginName: String = "",
    val classes: List<HostInstantClassEntry> = emptyList(),
    val newClasses: List<HostInstantClassEntry> = emptyList(),
)

/** One class payload: base64 [data] bytes plus the CLI's expected loaded CRC32 (0 for new). */
data class HostInstantClassEntry(
    val fqcn: String = "",
    val expectedCrc32: Long = 0,
    val data: String = "",
)

/** Mirror of the CLI's `InstantSwapStatus`; serialized as the lowercase wire values. */
enum class HostInstantSwapStatus {
  @SerializedName("ok") OK,
  @SerializedName("refused") REFUSED,
  @SerializedName("failed") FAILED,
}

/**
 * Mirror of the CLI's `InstantSwapReport`, sent as an `instantReport` message. [reason] is
 * user-facing on refusal/failure — the CLI prints it verbatim before falling through to the full
 * swap path.
 */
data class HostInstantSwapReport(
    val requestId: String,
    val status: HostInstantSwapStatus,
    val patched: Int = 0,
    val defined: Int = 0,
    val reason: String? = null,
    val durationMs: Long = 0,
)

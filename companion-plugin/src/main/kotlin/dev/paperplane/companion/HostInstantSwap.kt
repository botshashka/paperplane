package dev.paperplane.companion

import com.google.gson.annotations.SerializedName

/**
 * Mirror of the CLI's `InstantSwapRequest` — same JSON shape, no cross-module dependency. The CLI
 * asks for [classes] to be redefined in place (each verified against
 * [HostInstantClassEntry .expectedCrc32] before anything is touched) and [newClasses] to be made
 * loadable. Applied atomically by [InstantSwapper]; any check that fails refuses the whole request.
 */
data class HostInstantSwapRequest(
    val requestId: String = "",
    val pluginName: String = "",
    val classes: List<HostInstantClassEntry> = emptyList(),
    val newClasses: List<HostInstantClassEntry> = emptyList(),
)

/** One class payload; [expectedCrc32] is 0 for new classes, which have nothing loaded yet. */
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
 *
 * [appliedClasses] names every class now verifiably running the requested bytes. The CLI advances
 * its baseline for exactly these, so it must never include one the swapper skipped: counts alone
 * would let a silently-skipped class be recorded as patched, and the CLI would then vouch for bytes
 * the server never took.
 */
data class HostInstantSwapReport(
    val requestId: String,
    val status: HostInstantSwapStatus,
    val patched: Int = 0,
    val defined: Int = 0,
    val appliedClasses: List<String> = emptyList(),
    val reason: String? = null,
    val durationMs: Long = 0,
)

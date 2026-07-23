package dev.paperplane.cli.devserver

import com.google.gson.annotations.SerializedName
import dev.paperplane.cli.devserver.instant.ClassPatch
import java.util.Base64

/**
 * CLI→companion instant-patch request (`instantSwap` wire message): redefine [classes] in place and
 * make [newClasses] loadable, atomically — or refuse. Class bytes travel on the wire (base64)
 * rather than as filesystem paths: payloads are small (changed classes only) and the message stays
 * valid when the server has no shared filesystem (the future containerized Fresh engine).
 *
 * Mirror of the companion's `HostInstantSwapRequest`; the modules deliberately share no code, so
 * any shape change must land on both sides and bump `CompanionSocketFile.PROTOCOL_VERSION`.
 */
internal data class InstantSwapRequest(
    val requestId: String,
    val pluginName: String,
    val classes: List<InstantClassEntry> = emptyList(),
    val newClasses: List<InstantClassEntry> = emptyList(),
) {
  companion object {
    fun entry(patch: ClassPatch): InstantClassEntry =
        InstantClassEntry(
            fqcn = patch.fqcn,
            expectedCrc32 = patch.expectedLoadedCrc32,
            data = Base64.getEncoder().encodeToString(patch.bytes),
        )
  }
}

/**
 * One class payload. [expectedCrc32] is the CRC32 of the bytes the CLI believes are currently
 * loaded (0 for new classes); the companion refuses on mismatch instead of patching over drift.
 */
internal data class InstantClassEntry(
    val fqcn: String,
    val expectedCrc32: Long = 0,
    val data: String = "",
)

/**
 * Companion→CLI answer to an [InstantSwapRequest] (`instantReport` wire message).
 *
 * [appliedClasses] names every class the companion confirmed is now running the requested bytes.
 * The baseline advances for exactly these — never for everything requested, because the companion
 * can legitimately skip a class (an already-loaded "new" class, for one), and advancing past a skip
 * would leave the CLI vouching for bytes the server never took.
 *
 * No duration field: the lane times its own send→answer wall clock, which is what it reports, so a
 * server-side figure would only ever be a second unread number on the wire.
 */
internal data class InstantSwapReport(
    val requestId: String = "",
    val status: InstantSwapStatus? = null,
    val patched: Int = 0,
    val defined: Int = 0,
    val appliedClasses: List<String> = emptyList(),
    val reason: String? = null,
)

/**
 * `ok` — all classes applied atomically. `refused` — an eligibility or verification check said no
 * (baseline drift, plugin not loaded, leak limit, unspliceable classloader); nothing was touched.
 * `failed` — the JVM vetoed or the attempt errored; redefineClasses is all-or-nothing, so nothing
 * was applied either. Refused and failed both send the CLI down the mode's full swap path.
 */
internal enum class InstantSwapStatus {
  @SerializedName("ok") OK,
  @SerializedName("refused") REFUSED,
  @SerializedName("failed") FAILED,
}

/** Outcome of waiting for an [InstantSwapReport] — the instant sibling of [LoadWaitResult]. */
internal sealed class InstantWaitResult {
  data class Answered(val report: InstantSwapReport) : InstantWaitResult()

  object TimedOut : InstantWaitResult()

  object ServerExited : InstantWaitResult()
}

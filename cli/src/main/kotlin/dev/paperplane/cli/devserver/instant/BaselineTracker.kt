package dev.paperplane.cli.devserver.instant

/**
 * Tracks what the live server is *confirmed to be running* — the baseline every instant
 * classification diffs against.
 *
 * The advance rule is the correctness core (a wrong baseline means the CLI vouches for old bytes
 * the server doesn't have): the baseline moves only on **confirmation** — a full swap the mode
 * reported successful ([confirmFullSwap]) or an instant patch the companion reported applied
 * ([confirmPatched], per class). A candidate that classified NONE (debug-only diff) advances
 * nothing: the server still runs the baseline bytes, and the companion's loaded-CRC verification
 * would start refusing real patches if we pretended otherwise. A missed confirmation therefore
 * degrades to a refusal (safe direction), never to a silent mispatch.
 *
 * One tracker per server JVM — blue-green holds one per slot; a server death ([reset]) discards
 * the baseline until the replacement's first confirmed load reseeds it.
 */
class BaselineTracker {
  var baseline: BuildCandidate? = null
    private set

  val seeded: Boolean
    get() = baseline != null

  /** The mode's full swap path deployed [candidate] and the server confirmed it loaded. */
  fun confirmFullSwap(candidate: BuildCandidate) {
    baseline = candidate
  }

  /**
   * The companion confirmed redefining exactly [fqcns] from [candidate]. Only those classes
   * advance; everything else (including resources) stays at the loaded baseline.
   */
  fun confirmPatched(candidate: BuildCandidate, fqcns: Collection<String>) {
    val current = baseline ?: return
    val patched = current.classes.toMutableMap()
    for (fqcn in fqcns) {
      candidate.classes[fqcn]?.let { patched[fqcn] = it }
    }
    baseline = BuildCandidate(patched, current.resourceCrcs)
  }

  /** The server is gone (crash, restart pending) — nothing is confirmed running anymore. */
  fun reset() {
    baseline = null
  }
}

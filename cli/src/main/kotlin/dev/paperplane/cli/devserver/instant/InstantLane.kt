package dev.paperplane.cli.devserver.instant

import dev.paperplane.cli.devserver.DevSession
import dev.paperplane.cli.devserver.InstantSwapRequest
import dev.paperplane.cli.devserver.InstantSwapStatus
import dev.paperplane.cli.devserver.InstantWaitResult
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.ipc.CompanionWire
import dev.paperplane.cli.server.PaperServerManager
import java.io.File

/**
 * What one fast-lane attempt produced. The mode acts on it: [Patched]/[NoChange] end the rebuild
 * (report + keep watching), [Escalate] falls through to the mode's full swap path (printing
 * [Escalate.reason] when present), [CompileFailed] ends the rebuild as a build failure.
 */
internal sealed class InstantOutcome {
  data class Patched(val patchedCount: Int, val definedCount: Int, val durationMs: Long) :
      InstantOutcome()

  /** The rebuild produced no observable bytecode/resource change — nothing to do, say so. */
  object NoChange : InstantOutcome()

  /** Take the full swap path. [reason] is user-facing; null means escalate silently (lane off). */
  data class Escalate(val reason: String?) : InstantOutcome()

  object CompileFailed : InstantOutcome()
}

/**
 * The universal fast lane every dev mode runs in front of its swap path: compile, classify the
 * change-set against the confirmed baseline, and — when the requirement fits the live JVM's
 * capability and the lifecycle gate passes — patch the running server in place over the socket.
 * Everything else escalates with a named reason; escalation costs exactly one normal swap.
 *
 * One lane per server manager (blue-green constructs one per slot pair — the [BaselineTracker]
 * passed to [attempt] is per-slot). Owns the cached fast metadata; a build-config change (via
 * [DevSession.buildConfigChangeListeners]) drops the cache so a changed classes-dir layout can't
 * classify against stale paths.
 */
internal class InstantLane(private val session: DevSession) {

  companion object {
    /** In-place redefinition is near-instant; a longer wait means something is wrong anyway. */
    private const val REPORT_TIMEOUT_MS = 5_000L
  }

  private val classifier = ChangeClassifier()
  private var cachedFastMeta: ProjectMetadata? = null

  init {
    session.buildConfigChangeListeners += { cachedFastMeta = null }
  }

  /**
   * Runs one fast-lane attempt: compile (`classes` only — the jar is skipped entirely on the
   * patched path), classify, and patch or escalate. Emits the standard build success/failure
   * lines; the mode emits the outcome lines.
   */
  fun attempt(
      serverManager: PaperServerManager,
      metadata: ProjectMetadata,
      baseline: BaselineTracker,
  ): InstantOutcome {
    serverManager.sendCompanionStatus(CompanionWire.STATE_BUILDING)
    val buildStart = System.currentTimeMillis()
    val buildSuccess = session.gradle.compileOnly()
    val buildDuration = session.formatDuration(System.currentTimeMillis() - buildStart)
    if (!buildSuccess) {
      session.ui.error("Build failed", buildDuration)
      serverManager.sendCompanionStatus(CompanionWire.STATE_ERROR, message = "Build failed")
      return InstantOutcome.CompileFailed
    }
    session.ui.success("Build succeeded", buildDuration)

    if (!session.config.dev.instant) return InstantOutcome.Escalate(null)
    if (!serverManager.isRunning()) return InstantOutcome.Escalate("server not running")
    if (!baseline.seeded) return InstantOutcome.Escalate("no confirmed baseline yet")
    val fastMeta = fastMeta() ?: return InstantOutcome.Escalate("build metadata unavailable")
    if (fastMeta.effectiveClassesDirs.isEmpty()) {
      return InstantOutcome.Escalate("no class output dirs in build metadata")
    }
    val (capability, capNote) = effectiveCapability(serverManager, metadata)
    if (capability == RedefineCapability.NONE) {
      return InstantOutcome.Escalate("server JVM reports no redefine capability")
    }

    val candidate = capture(fastMeta)
    val classification =
        classifier.classify(baseline.baseline!!, candidate, metadata.mainClass)

    if (classification.requirement == RedefineRequirement.NONE) return InstantOutcome.NoChange
    if (classification.requirement == RedefineRequirement.UNSAFE) {
      return InstantOutcome.Escalate(classification.escalations.first().description)
    }
    if (!fitsWithin(classification.requirement, capability)) {
      val what = classification.additiveNotes.firstOrNull() ?: "structural change"
      val why = capNote ?: "needs JBR enhanced redefinition (dev.jbr: on)"
      return InstantOutcome.Escalate("$what — $why")
    }

    return sendAndAwait(serverManager, metadata, baseline, candidate, classification)
  }

  /** Requirement-vs-capability lattice: patch iff the live JVM admits everything in the set. */
  private fun fitsWithin(
      requirement: RedefineRequirement,
      capability: RedefineCapability,
  ): Boolean =
      when (requirement) {
        RedefineRequirement.NONE -> true
        RedefineRequirement.BODY_ONLY -> capability >= RedefineCapability.BODY_ONLY
        RedefineRequirement.ADDITIVE -> capability >= RedefineCapability.ADDITIVE
        RedefineRequirement.UNSAFE -> false
      }

  private fun sendAndAwait(
      serverManager: PaperServerManager,
      metadata: ProjectMetadata,
      baseline: BaselineTracker,
      candidate: BuildCandidate,
      classification: InstantClassification,
  ): InstantOutcome {
    val request =
        InstantSwapRequest(
            requestId = InstantSwapRequest.newId(),
            pluginName = metadata.pluginName,
            classes = classification.patches.map { InstantSwapRequest.entry(it) },
            newClasses = classification.newClasses.map { InstantSwapRequest.entry(it) },
        )
    val patchStart = System.currentTimeMillis()
    if (!serverManager.sendInstantSwap(request)) {
      return InstantOutcome.Escalate("companion connection unavailable")
    }
    val result =
        session.ui.spin("Patching ${metadata.pluginName}...") {
          serverManager.awaitInstantReport(request.requestId, REPORT_TIMEOUT_MS)
        }
    return when (result) {
      is InstantWaitResult.Answered ->
          when (result.report.status) {
            InstantSwapStatus.OK -> {
              // The baseline advances for exactly the classes the companion confirmed applied.
              baseline.confirmPatched(
                  candidate,
                  classification.patches.map { it.fqcn } +
                      classification.newClasses.map { it.fqcn },
              )
              InstantOutcome.Patched(
                  patchedCount = result.report.patched,
                  definedCount = result.report.defined,
                  durationMs = System.currentTimeMillis() - patchStart,
              )
            }
            InstantSwapStatus.REFUSED,
            InstantSwapStatus.FAILED,
            null -> InstantOutcome.Escalate(result.report.reason ?: "patch not applied")
          }
      InstantWaitResult.TimedOut -> InstantOutcome.Escalate("no patch answer from the companion")
      InstantWaitResult.ServerExited -> InstantOutcome.Escalate("server exited during the patch")
    }
  }

  /** Emits the standard patched lines + READY status — shared by every mode's patched path. */
  fun reportPatched(
      serverManager: PaperServerManager,
      outcome: InstantOutcome.Patched,
      totalStart: Long,
  ) {
    val totalDuration = session.formatDuration(System.currentTimeMillis() - totalStart)
    session.ui.success(
        "Patched ${outcome.patchedCount + outcome.definedCount} " +
            (if (outcome.patchedCount + outcome.definedCount == 1) "class" else "classes") +
            " (instant)",
        session.formatDuration(outcome.durationMs),
    )
    session.ui.totalTime(totalDuration)
    serverManager.sendCompanionStatus(CompanionWire.STATE_READY, duration = totalDuration)
  }

  /** Emits the honest nothing-to-do line — the rebuild changed no observable bytecode. */
  fun reportNoChange(serverManager: PaperServerManager) {
    session.ui.success("No code changes")
    serverManager.sendCompanionStatus(CompanionWire.STATE_READY)
  }

  /**
   * Captures the current build output and promotes it to [baseline]'s confirmed state. Modes call
   * this at their existing full-swap success points (server ready, reload Ok, fix recovered) —
   * the moments the server is verifiably running the current build.
   */
  fun confirmFullSwap(baseline: BaselineTracker) {
    val fastMeta = fastMeta() ?: return
    baseline.confirmFullSwap(capture(fastMeta))
  }

  /**
   * The session-start banner value for "Instant:" — always report the tier ceiling and why.
   * Resolved against the live server, so it must be called after the companion handshake.
   */
  fun capabilityLabel(serverManager: PaperServerManager, metadata: ProjectMetadata): String {
    if (!session.config.dev.instant) return "off (dev.instant: false)"
    val (capability, capNote) = effectiveCapability(serverManager, metadata)
    return when {
      capability == RedefineCapability.NONE -> "off (no agent in the server JVM)"
      capability == RedefineCapability.ADDITIVE -> "additive (JBR enhanced redefinition)"
      capNote != null -> "body-only ($capNote)"
      else -> "body-only"
    }
  }

  /**
   * The live JVM's capability, capped to body-only when a curated reflection-discovery framework
   * is present (see [ReflectionFrameworkList]); the note says why so the cap is never silent.
   */
  private fun effectiveCapability(
      serverManager: PaperServerManager,
      metadata: ProjectMetadata,
  ): Pair<RedefineCapability, String?> {
    val raw = serverManager.redefineCapability()
    if (raw != RedefineCapability.ADDITIVE) return raw to null
    val hit = ReflectionFrameworkList.match(metadata)
    return if (hit == null) raw to null
    else RedefineCapability.BODY_ONLY to "capped: $hit discovers methods reflectively"
  }

  private fun fastMeta(): ProjectMetadata? =
      cachedFastMeta ?: session.gradle.metadataFast().metadataOrNull?.also { cachedFastMeta = it }

  private fun capture(fastMeta: ProjectMetadata): BuildCandidate =
      BuildCandidate.capture(
          fastMeta.effectiveClassesDirs.map(::File),
          fastMeta.resourcesDir.takeIf { it.isNotEmpty() }?.let(::File),
      )
}

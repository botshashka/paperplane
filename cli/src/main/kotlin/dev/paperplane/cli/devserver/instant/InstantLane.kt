package dev.paperplane.cli.devserver.instant

import dev.paperplane.cli.devserver.DevSession
import dev.paperplane.cli.devserver.InstantSwapRequest
import dev.paperplane.cli.devserver.InstantSwapStatus
import dev.paperplane.cli.devserver.InstantWaitResult
import dev.paperplane.cli.devserver.newRequestId
import dev.paperplane.cli.gradle.ProjectMetadata
import dev.paperplane.cli.ipc.CompanionWire
import dev.paperplane.cli.server.PaperServerManager
import dev.paperplane.cli.ui.TerminalUI.PhaseEnd
import java.io.File

/**
 * What one fast-lane attempt produced. The mode acts on it: [Patched]/[NoChange] end the rebuild
 * (report + keep watching), [Escalate] and [Disabled] fall through to the mode's full swap path,
 * [CompileFailed] ends the rebuild as a build failure.
 */
internal sealed class InstantOutcome {
  data class Patched(val patchedCount: Int, val durationMs: Long) : InstantOutcome()

  /** The rebuild produced no observable bytecode/resource change — nothing to do, say so. */
  object NoChange : InstantOutcome()

  /** Take the full swap path and print [reason] — the lane tried and declined to vouch. */
  data class Escalate(val reason: String) : InstantOutcome()

  /**
   * The lane is switched off (`dev.instant: false`), so there is nothing to report — take the full
   * swap path silently. Distinct from [Escalate] because "no reason to print" is a state of the
   * lane, not a nameless escalation.
   */
  object Disabled : InstantOutcome()

  object CompileFailed : InstantOutcome()
}

/**
 * The universal fast lane every dev mode runs in front of its swap path: compile, classify the
 * change-set against the confirmed baseline, and — when the requirement fits the live JVM's
 * capability and the lifecycle gate passes — patch the running server in place over the socket.
 * Everything else escalates with a named reason; escalation costs exactly one normal swap.
 *
 * One lane per server manager (blue-green constructs one per slot pair — the [BaselineTracker]
 * passed to [attempt] is per-slot). Build paths come from [DevSession.fastMetadata], which a
 * build-config change invalidates, so a changed classes-dir layout can't classify against stale
 * paths.
 */
internal class InstantLane(private val session: DevSession) {

  companion object {
    /**
     * The patch itself is near-instant, but [handleInstantSwap][CompanionMessageHandler] is
     * dispatched on the server's next tick — so the budget has to cover a main-thread stall (chunk
     * generation, a long GC), not just the redefinition. Matched to the reload timeout: a tighter
     * one just turns a slow tick into a spurious full swap.
     */
    private const val REPORT_TIMEOUT_MS = 10_000L
  }

  private val classifier = ChangeClassifier()

  /**
   * Runs one fast-lane attempt: compile (`classes` only — the jar is skipped entirely on the
   * patched path), classify, and patch or escalate. Emits the standard build success/failure lines;
   * the mode emits the outcome lines.
   */
  fun attempt(
      serverManager: PaperServerManager,
      metadata: ProjectMetadata,
      baseline: BaselineTracker,
  ): InstantOutcome {
    if (!compile(serverManager)) return InstantOutcome.CompileFailed

    if (!session.config.dev.instant) return InstantOutcome.Disabled
    val fastMeta = fastMeta()
    val confirmed = baseline.confirmed()
    preconditionFailure(serverManager, confirmed, fastMeta)?.let {
      return InstantOutcome.Escalate(it)
    }
    return if (serverManager.ipc.redefineCapability() == RedefineCapability.NONE) {
      InstantOutcome.Escalate("server JVM reports no redefine capability")
    } else {
      classifyAndSend(serverManager, metadata, baseline, confirmed!!, fastMeta!!)
    }
  }

  /**
   * The rebuild's compile step (`classes` only) with the build-state broadcast and the standard
   * success/failure lines. Shared with the mode paths that skip the lane entirely (manual full
   * swap, awaiting-fix cold start) so a lane-less rebuild can't report differently than a lane one.
   */
  fun compile(serverManager: PaperServerManager): Boolean {
    serverManager.ipc.sendStatus(CompanionWire.STATE_BUILDING)
    val buildStart = System.currentTimeMillis()
    val buildSuccess = session.gradle.compileOnly()
    val buildDuration = session.formatDuration(System.currentTimeMillis() - buildStart)
    if (!buildSuccess) {
      session.ui.error("Build failed", buildDuration)
      serverManager.ipc.sendStatus(CompanionWire.STATE_ERROR, message = "Build failed")
      return false
    }
    session.ui.success("Build succeeded", buildDuration)
    return true
  }

  private fun classifyAndSend(
      serverManager: PaperServerManager,
      metadata: ProjectMetadata,
      baseline: BaselineTracker,
      confirmed: BuildCandidate,
      fastMeta: ProjectMetadata,
  ): InstantOutcome {
    val candidate = capture(fastMeta)
    val classification = classifier.classify(confirmed, candidate, metadata.mainClass)
    return when (classification.requirement) {
      RedefineRequirement.NONE -> InstantOutcome.NoChange
      RedefineRequirement.UNSAFE ->
          InstantOutcome.Escalate(
              classification.escalations.firstOrNull()?.description ?: "unsafe change"
          )
      RedefineRequirement.BODY_ONLY ->
          sendAndAwait(serverManager, metadata, baseline, candidate, classification)
    }
  }

  /** The user-facing reason the lane can't run at all, or null when every precondition holds. */
  private fun preconditionFailure(
      serverManager: PaperServerManager,
      confirmed: BuildCandidate?,
      fastMeta: ProjectMetadata?,
  ): String? =
      when {
        !serverManager.isRunning() -> "server not running"
        confirmed == null -> "no confirmed baseline yet"
        fastMeta == null -> "build metadata unavailable"
        fastMeta.effectiveClassesDirs.isEmpty() -> "no class output dirs in build metadata"
        else -> null
      }

  /**
   * One attempt plus its reporting, shared by every mode: a terminal [PhaseEnd] when the lane
   * handled the rebuild, or null meaning "escalate — run your full swap path". [fallbackLabel]
   * names that path in the escalation line ("full restart", "full swap", "full reload").
   */
  fun runOrEscalate(
      serverManager: PaperServerManager,
      metadata: ProjectMetadata,
      baseline: BaselineTracker,
      totalStart: Long,
      fallbackLabel: String,
  ): PhaseEnd? =
      when (val outcome = attempt(serverManager, metadata, baseline)) {
        is InstantOutcome.Patched -> {
          reportPatched(serverManager, outcome, totalStart)
          PhaseEnd.Watching
        }
        InstantOutcome.NoChange -> {
          reportNoChange(serverManager)
          PhaseEnd.Watching
        }
        InstantOutcome.CompileFailed -> PhaseEnd.Waiting
        is InstantOutcome.Escalate -> {
          session.ui.info("Instant:", "${outcome.reason} — $fallbackLabel")
          null
        }
        InstantOutcome.Disabled -> null
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
            requestId = newRequestId(),
            pluginName = metadata.pluginName,
            classes = classification.patches.map { InstantSwapRequest.entry(it) },
        )
    val patchStart = System.currentTimeMillis()
    if (!serverManager.ipc.sendInstantSwap(request)) {
      return InstantOutcome.Escalate("companion connection unavailable")
    }
    val result =
        session.ui.spin("Patching ${metadata.pluginName}...") {
          serverManager.ipc.awaitInstantReport(request.requestId, REPORT_TIMEOUT_MS)
        }
    return when (result) {
      is InstantWaitResult.Answered ->
          when (result.report.status) {
            InstantSwapStatus.OK -> {
              baseline.confirmPatched(candidate, result.report.appliedClasses)
              InstantOutcome.Patched(
                  patchedCount = result.report.patched,
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
        "Patched ${outcome.patchedCount} " +
            (if (outcome.patchedCount == 1) "class" else "classes") +
            " (instant)",
        session.formatDuration(outcome.durationMs),
    )
    session.ui.totalTime(totalDuration)
    serverManager.ipc.sendStatus(CompanionWire.STATE_READY, duration = totalDuration)
  }

  /** Emits the honest nothing-to-do line — the rebuild changed no observable bytecode. */
  fun reportNoChange(serverManager: PaperServerManager) {
    session.ui.success("No code changes")
    serverManager.ipc.sendStatus(CompanionWire.STATE_READY)
  }

  /**
   * Captures the current build output and promotes it to [baseline]'s confirmed state. Modes call
   * this at their existing full-swap success points (server ready, reload Ok, fix recovered) — the
   * moments the server is verifiably running the current build.
   */
  fun confirmFullSwap(baseline: BaselineTracker) {
    // No metadata means we cannot capture what the server just loaded. Silently returning would
    // leave the tracker vouching for the PREVIOUS build while the server runs this one — the
    // classifier would then diff against bytes nobody is running. Drop to unseeded instead: the
    // next rebuild escalates with "no confirmed baseline yet", which is the safe direction.
    val fastMeta = fastMeta() ?: return baseline.reset()
    baseline.confirmFullSwap(capture(fastMeta))
  }

  /**
   * The session-start banner value for "Instant:" — always report the tier ceiling and why.
   * Resolved against the live server, so it must be called after the companion handshake.
   */
  fun capabilityLabel(serverManager: PaperServerManager): String =
      when {
        !session.config.dev.instant -> "off (dev.instant: false)"
        serverManager.ipc.redefineCapability() == RedefineCapability.NONE ->
            "off (no agent in the server JVM)"
        else -> "body-only"
      }

  private fun fastMeta(): ProjectMetadata? = session.fastMetadata()

  private fun capture(fastMeta: ProjectMetadata): BuildCandidate =
      BuildCandidate.capture(
          fastMeta.effectiveClassesDirs.map(::File),
          fastMeta.resourcesDir.takeIf { it.isNotEmpty() }?.let(::File),
      )
}

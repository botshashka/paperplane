package dev.paperplane.cli.devserver

import com.github.ajalt.clikt.core.ProgramResult
import dev.paperplane.cli.config.DevMode
import dev.paperplane.cli.config.FallbackPolicy
import dev.paperplane.cli.gradle.MetadataResult
import dev.paperplane.cli.ui.InteractivePrompts
import dev.paperplane.cli.ui.TerminalUI

/**
 * Session-start mode selection: decides which dev mode the session actually runs, before any mode
 * object exists (concept §5).
 *
 * For a hot-reload request it preflights project metadata (cached on the session so the dispatched
 * mode doesn't re-run Gradle) and scans the [ModeSelector] sources. On a categorical rejection it
 * runs the consent flow:
 * - `dev.fallback: ask` (default) — offer "Switch this session to <native mode>? (Y/n)". Declining
 *   ends the session; hot-reload categorically cannot run.
 * - `dev.fallback: auto` — demote without asking, behind a loud banner.
 * - `ask` without a TTY — nobody can answer, so fail loudly naming the config keys that unblock a
 *   headless run.
 *
 * The rejection banner always prints before any of the three paths — the session never swaps modes
 * silently. The demotion target is `restart` (the lowest-infrastructure native mode) unless the
 * project's configured mode is blue-green — then its proxy infrastructure already exists and is
 * what the user runs daily, so demote to that instead of introducing a third mode.
 *
 * Native-mode requests pass straight through: restart and blue-green have no categorical rejections
 * today. Fresh mode later becomes another selectable tier here without reshaping the flow.
 */
internal class ModeSelectionFlow(
    private val ui: TerminalUI,
    private val prompts: InteractivePrompts,
    private val selector: ModeSelector = ModeSelector(),
) {

  /**
   * Returns the mode the session should run — [requested] itself, or the native mode a rejection
   * demoted to (recorded on the session for the tier report). Null means the session must not
   * start: the user declined the demotion. A non-interactive session that would need the prompt
   * throws [ProgramResult] instead, so headless runs exit non-zero.
   */
  fun resolve(session: DevSession, requested: DevMode, configuredMode: DevMode): DevMode? {
    if (requested != DevMode.HOT_RELOAD) return requested

    val preflight = session.preflightMetadata()
    // No PaperPlane gradle plugin: the dispatched mode prints the ppl init hint and exits — a
    // demotion conversation before that would be noise about a session that cannot start anyway.
    if (preflight == MetadataResult.PluginNotApplied) return requested

    // A failed build has no metadata; the selector still scans the config-side sources. Whatever
    // it can't see here is enforced in-session once metadata resolves
    // (enforceHotReloadEligibility).
    val rejections =
        selector.rejections(
            requested,
            session.config,
            preflight.metadataOrNull,
            session.serverPluginsDir,
        )
    if (rejections.isEmpty()) return requested

    val target = if (configuredMode == DevMode.BLUE_GREEN) DevMode.BLUE_GREEN else DevMode.RESTART

    ui.block {
      warning("Hot-reload is unavailable for this session:")
      rejections.forEach { status("${it.matchedBy} — ${it.reason}") }
    }

    return when {
      session.config.dev.fallback == FallbackPolicy.AUTO -> {
        ui.block {
          warning("Falling back to ${target.label} for this session (dev.fallback: auto)")
        }
        demote(session, target, rejections)
      }
      !prompts.isInteractive -> {
        ui.block {
          error("Cannot offer the ${target.label} fallback: no interactive terminal.")
          status(
              "Set `dev.fallback: auto` in paperplane.yml to fall back automatically, " +
                  "or set `dev.mode: ${target.label}`."
          )
        }
        throw ProgramResult(1)
      }
      prompts.confirm("Switch this session to ${target.label}?", default = true) ->
          demote(session, target, rejections)
      else -> {
        ui.block {
          error("Exiting — hot-reload cannot run with the rejection above.")
          status("Switch permanently with `dev.mode: ${target.label}` in paperplane.yml.")
        }
        null
      }
    }
  }

  private fun demote(
      session: DevSession,
      target: DevMode,
      rejections: List<ModeRejection>,
  ): DevMode {
    session.demoteMode(target, rejections)
    return target
  }
}

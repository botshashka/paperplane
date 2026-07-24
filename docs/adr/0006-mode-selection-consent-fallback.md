# 0006 — Categorical mode rejection with consent-based fallback

**Status:** Accepted
**Date:** 2026-07-24

## Context

Hot-reload — the fastest dev mode — works by loading the user's plugin into
an already-running server and swapping it in place on rebuilds. Two classes
of dependency make that categorically wrong, not merely flaky:

- **Boot-wiring dependencies** do their setup during server bootstrap and
  cannot repeat it for a plugin that arrives late. CommandAPI is the seed
  example: its NMS-level command wiring never happens for a late-loaded
  plugin, so the plugin's commands silently don't exist. No load-order or
  host improvement can fix this — the constraint is *timing* (post-boot
  arrival), not the host mechanism.
- **Stale-reference holders** cache objects from the reloaded plugin's
  classloader across an in-place swap. ProtocolLib is the seed example: it
  keeps hold of packet listeners registered by the old plugin instance.

Until now nothing checked for either: `ppl dev` would start cleanly and the
session would misbehave in ways that look like user bugs. That violates the
project's core positioning — other tools compete on when they work; this
tool competes on never being silently wrong.

A third categorical rejection already existed — the Paper 1.19.3 version
floor (the first version with `ConfiguredPluginClassLoader`, which the
companion host implements) — but it was a hard throw that killed the
session rather than offering a way forward.

Some incompatibilities are *declared* machine-readably (`load: STARTUP`,
paper-plugin.yml bootstrappers) and are the companion probe's job. The
classes above are *behavioral*: no metadata announces them, and static
inference from bytecode is not a realistic alternative. Prior art for
hot-code tooling (e.g. JRebel) is substantially curated per-framework
compatibility knowledge.

## Decision

At `ppl dev` session start, before any server exists, evaluate the
requested mode against a **curated can't-late-load list** plus the version
floor, and on any hit run a **consent-based demotion flow**. Three parts:

- **The scanner (`ModeSelector`) is pure and rule-driven.** Rules are data:
  id, plugin-name set, anchored artifact pattern, and a human reason string
  that feeds every user-facing surface. Seed list: CommandAPI,
  ProtocolLib. Sources scanned, degrading gracefully when one is absent:
  `plugin.yml` `depend`/`softdepend` and the runtime classpath (both from
  metadata.json), `server.plugins` entries in `paperplane.yml`, and jars
  already present in the dev server's `plugins/` directory. Presence is
  enough to reject — safety over precision; whether the user's plugin
  actually touches the dependency is not inferred. The version floor is
  one more rejection in the same shape, not a separate mechanism.
- **Consent, never silence.** On rejection the session prints what matched
  and why, then per `dev.fallback`: `ask` (default) offers "Switch this
  session to <native mode>? (Y/n)" — bare Enter accepts, declining ends
  the session, EOF is never consent; `auto` demotes without asking but
  behind the same banner. A non-interactive `ask` session fails with exit
  code 1 naming the config keys that unblock it. **The mode never changes
  silently** — that invariant, not the prompt, is the contract. Demotion
  is session-scoped; the config file is never rewritten. The demotion
  target is `restart` (lowest-infrastructure native mode), except when the
  configured mode is blue-green and hot-reload came from `--mode` — then
  the project's existing blue-green infrastructure beats introducing a
  third mode. There is deliberately **no override** to force hot-reload
  past a rejection: the surviving rejections are deterministic breakage,
  and an override would be a footgun with a banner on it. Conditional
  cases belong to a future warn-severity tier of the rule data, not to a
  user escape hatch.
- **The tier report.** Every session states the mode it runs; a demoted
  session's server-info block names the requested mode and the concrete
  match that rejected it. Selection runs once at session start; an
  in-session backstop re-checks eligibility wherever metadata resolves
  (initial build, fix recovery) and fails loudly with guidance — covering
  sessions whose broken initial build hid their dependencies, and build
  edits that add a curated dependency mid-session. No mid-session
  demotion: the mode is already running.

## Consequences

- Hot-reload sessions that would have silently misbehaved now either run a
  native mode with the user's informed consent or don't start — the
  failure is moved from "confusing runtime behavior" to "one explained
  question at startup".
- The demoted-native path stays fast where it matters: the instant tier
  (ADR 0005) runs in every mode, so body-only edits still patch in
  sub-second even after demotion.
- A false positive in the curated list costs speed, never correctness —
  the demotion target is always full-fidelity. A false negative
  (unlisted plugin) is caught downstream by the leak-attribution
  diagnostics, which name the stale-classloader holder and thereby
  nominate the next list entry. List additions are one data entry.
- The scanner keys on names and artifact patterns: vendored or relocated
  copies of a listed library evade it by construction. Accepted; the
  runtime backstop above is the net.
- The selection machinery is tier-agnostic (`rejections(mode, …)`): future
  reload strategies become selectable tiers by adding rules, not by
  reshaping the flow. Rules are expected to grow per-tier applicability
  (a stale-reference rule can reject an in-place-reload tier while
  allowing a fresh-server tier) and a warn-vs-block severity.
- `dev.fallback` is public config surface and `ask` semantics are now a
  compatibility contract for scripts (exit 1 + named keys in headless
  runs).

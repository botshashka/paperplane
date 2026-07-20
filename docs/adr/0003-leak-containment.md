# 0003 — Leak containment over leak prevention

**Status:** Accepted
**Date:** 2026-07-20

## Context

Unloading code from a running JVM is unreliable by nature: a classloader is
only collectable when nothing references it, and a single stale reference — a
plugin thread still running, a listener left registered, an object cached in
another plugin's static — pins the entire old plugin, its classes, and its
metaspace. This is the fifteen-year-old "PlugMan problem," and no teardown
pipeline can fully solve it, because the references that pin a loader can
live in code we don't control.

Tools in this space historically pretend otherwise: reload "succeeds," the
server slowly degrades, and the user discovers the truth via
OutOfMemoryError or silently stale behavior hours later.

## Decision

Treat leaks as a fact of life to be **detected, attributed, and bounded** —
never denied:

- **Teardown does everything teardown can do**, via public API wherever one
  exists: unregister commands and restore displaced help topics, sweep
  Brigadier lifecycle command nodes, remove recipes, boss bars, and chunk
  tickets, cancel tasks, and interrupt plugin-owned threads (bounded join,
  2s). Teardown steps are individually fault-isolated — one failing step
  doesn't abort the rest.
- **Detection is honest:** after teardown, dead loaders are tracked by
  `WeakReference` and counted with double-GC survivor confirmation. A loader
  that survives two forced collections is reported as a leak, not hoped away.
- **Attribution over accusation:** confirmed leaks come with evidence —
  surviving plugin-owned threads with truncated stacks, and (when the
  diagnostics channel requests it) deferred heap dumps for offline analysis.
  `LeakDiagnosticsMode` gates only the verbosity; counting and attribution
  always run, because the load report and auto-restart depend on them.
- **A leak budget, then a fresh JVM:** the host declares the leak limit
  reached after 3 consecutive leaking reloads *or* 5 total surviving loaders
  (the absolute cap catches the alternating leak/clean case, where the
  consecutive counter keeps resetting while metaspace still grows). The
  `LoadReport` carries the leak summary and a restart action; the CLI
  restarts the server and keeps the dev loop alive. The only guaranteed
  cleanup on the JVM is process death, so that is the backstop.

## Consequences

- A leaking reload is loud and explained ("leaked, N survivors, these
  threads") instead of silently corrupting the session.
- The dev loop is self-healing: worst case is a bounded number of degraded
  reloads followed by an automatic clean restart — never an unbounded slide
  into OOM.
- Users get a signal about *their* bugs (threads not stopped in `onDisable`,
  listeners leaked into other plugins) while iterating, not in production.
- Forced GCs and survivor bookkeeping add a small cost per reload, paid only
  in hot-reload mode.
- The limit values (3 consecutive / 5 absolute) are pragmatic dials, not
  science; they encode "tolerate a bad patch-cycle, don't tolerate a trend."

# 0002 — File-based JSON load protocol

**Status:** Accepted
**Date:** 2026-07-20

## Context

The CLI and the companion plugin live in different JVMs and need to
coordinate reloads: the CLI knows when a build finished and what changed; the
companion knows whether the load succeeded and what it cost. Early versions
used bare flag files (`reload-complete` / `reload-failed`), which carried no
payload — no way to distinguish *which* reload completed, why a load failed,
or whether teardown leaked.

## Decision

Keep the transport the CLI and companion already share — files in the server's
`.paperplane/` directory — but make the messages structured JSON with
request/response correlation:

- **`LoadRequest`** (CLI → companion, `load-request.json`): carries a UUID
  `requestId`, the staged jar path, plugin name, build-output directories,
  runtime classpath, and the list of changed classes. The companion derives
  the reload strategy from the contents alone (changed classes with no
  structural edits → hotswap via Instrumentation; classes dirs → directory
  reload; otherwise jar reload) — there is no separate strategy channel.
- **`LoadReport`** (companion → CLI, `load-complete` / `load-failed`): echoes
  the `requestId` so the CLI's waiter can discard stale reports from a
  previous cycle, and carries status, strategy actually used, duration,
  structured failure message, and the leak summary / recommended action from
  ADR-0003.
- **Torn-read tolerance at both ends:** every write goes through
  tmp-file-then-atomic-move (with a Windows sharing-violation fallback that
  retries as a non-atomic replace), and every `LoadReport` field has a
  default so a partial document deserializes without throwing.
- Ambient signals stay as simple flag/status files: `server-ready`,
  `companion-status.json` (build state for the action bar), `save-complete`.

## Consequences

- Failed loads become actionable: the CLI can print the real cause and drive
  a fix-recovery loop instead of guessing from a bare flag.
- `requestId` matching makes rapid successive rebuilds safe — a slow report
  from reload N cannot be mistaken for the result of reload N+1.
- The schema (request/report semantics, requestId correlation) is independent
  of the transport; a future move to a socket changes delivery, not meaning.
- The costs of the file transport are real and accepted for now: polling
  latency on every hop, no streamed progress, and the atomic-move/Windows
  retry machinery exists purely to protect readers from partial files.

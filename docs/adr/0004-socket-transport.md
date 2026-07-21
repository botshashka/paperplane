# 0004 — Localhost TCP socket transport for the CLI↔companion protocol

**Status:** Accepted
**Date:** 2026-07-21
**Supersedes:** [0002](0002-file-based-json-load-protocol.md)

## Context

ADR-0002 deliberately kept the message *schema* independent of its transport,
and the file transport's accepted costs came due: polling latency on every
hop (the companion polled every 5 ticks, the CLI every 100–200 ms), no way to
stream progress during a load, and a body of atomic-move/claim-rename/Windows
sharing-violation retry machinery that existed purely to protect readers from
partial files. The next-generation architecture also needs capabilities a
file transport cannot express: streamed load events as the primary headless
UX, and a connection whose loss *is* the crash signal across server restores.

One empirical finding shaped the design: connect-level probes false-pass.
A completed TCP handshake proves nothing about the peer (kernel backlogs
complete handshakes for listeners that never accept), and even an accepted,
authenticated connection only proves the *companion* enabled — not that the
server finished startup.

## Decision

Same JSON message schema (`LoadRequest`/`LoadReport`, `requestId`
correlation), new transport: **NDJSON over localhost TCP** — not Unix domain
sockets, which fail the container boundary and are second-class on Windows.

- **The companion hosts the socket.** It binds an ephemeral loopback port at
  enable time and publishes `{port, token, protocolVersion}` to
  `.paperplane/companion-socket.json` — the one file left in the protocol,
  used only for discovery. The CLI deletes it before each server launch so a
  stale file can never dial a reassigned port.
- **The CLI owns the connection** and a redial loop: it polls for the
  handshake file, connects, and authenticates with the file's random token
  (`hello`/`welcome`). A newly authenticated connection replaces the previous
  one, which is how the CLI reconnects across its own restarts — and, later,
  across Fresh-mode restores.
- **Liveness:** an established connection replaces the `server-ready` flag
  file as the companion-liveness signal; a dropped connection is the crash
  signal — every CLI await treats disconnection as the server dying.
- **Readiness is an explicit streamed event**, never a connection
  side-effect: the companion streams `ready` on `ServerLoadEvent` and
  snapshots the state into the `welcome` for late-connecting CLIs.
- **Streamed events replace the remaining flag files:** `loadProgress`
  (`loading` today; Fresh mode will add stages) and `report` replace
  `load-complete`/`load-failed`; `saveComplete` replaces `save-complete`;
  the CLI→companion `status` message replaces `companion-status.json`; the
  leak-diagnostics mode rides on the load request, replacing
  `companion-config.json`.
- **Typed schema both ends:** status and strategy are enums
  (`ok`/`failed`, `hotswap`/`fresh`/`reload`) on both sides of the wire,
  still serialized as the ADR-0002 wire strings.
- **Debug tee:** `dev.protocol-log: true` appends every message (both
  directions, handshake included, raw lines verbatim) to
  `.paperplane/protocol-log.ndjson` — the forensic record and the source of
  the captured-real-fixture replay tests in both modules.
- **One survivor on disk:** `companion-error` remains a file, because a
  companion that failed to enable may never have constructed (or is about to
  tear down) the socket the message would otherwise travel over. The CLI's
  dial loop polls it and aborts with the real cause.

The cut is clean: all flag-file machinery — polling loops, claim-renames,
move-retries, the companion's `AtomicMove` — is deleted, with no
compatibility period. CLI and companion ship as one artifact, so no version
skew exists to be compatible with; the `hello`/`welcome` version check is
belt-and-braces for stale server directories.

## Consequences

- Round-trip latency drops from polling-bound (~250–500 ms floor) to
  effectively instant; the world save and load handoffs are push-driven.
- Streamed `LoadReport`s become machine-consumable as they happen — the
  primary interface for headless/agent operation in the next architecture.
- The companion gained an accept/reader thread pair; all message handling
  still hops to the server main thread, preserving the old single-threaded
  discipline.
- Reports are no longer durable: a report emitted while no CLI is connected
  is dropped (the old on-disk result could outlive a CLI restart). Accepted —
  a restarted CLI sends fresh requests with fresh ids and would have
  discarded the stale report anyway.
- The token closes the port-squatting/cross-talk hole a bare localhost port
  would open, at the cost of one extra field in the discovery file.

# 0007 — The world-refresh primitive: copy-on-write sync and secondary-world reload (protocol v5)

**Status:** Accepted
**Date:** 2026-07-24

## Context

Fresh mode (concept §10 step 6) boots a clean server for every rebuild. A clean
server needs the *current* world, not the one the last image was built from, so
every iteration has to move a world directory from wherever the live state is
into wherever the fresh server will read it — inside a loop whose whole budget
is `build + ~0.5 s`. A worlds directory is tens to hundreds of megabytes; a
byte copy at that size costs more than the rest of the loop combined.

Three forces shaped the design, and a fourth was found by building it.

**Copying is too slow, but the filesystem already solves it.** APFS and
btrfs/XFS can clone a directory tree by sharing extents — measured at 5–58 ms
for 1 GB on APFS and ~2 ms per 200 MB on reflink (gate 5). No JDK API exposes
clonefile or reflink, so reaching them means either bundling native code or
exec'ing `cp`. Not every filesystem has the capability at all: ext4 does not,
and Windows has no equivalent this project has validated.

**Bukkit cannot unload the default world.** `unloadWorld` refuses it outright,
so a design that refreshes "the world" in place is impossible. The dev world
has to be a *secondary* world, with the server's own `level-name` pointing at
something small and permanent — the §2.4 void-only pattern.

**Ordering is a correctness property, not a preference.** If the plugin loads
before the world is refreshed, `onEnable` captures `World` references that the
refresh then invalidates, and the plugin spends the session holding handles to
an unloaded world. This is the same stale-reference class Fresh mode exists to
kill, reintroduced by the very mechanism meant to serve it. Concept §2.1 states
the required order; stating it is not enforcing it.

**And the save was already lying.** Modern Paper saves asynchronously: the
unflushed `World#save()` — like the `save-all flush` console command — returns
once the save is *scheduled*. Blue-green already trusted `saveComplete` as
permission to start copying, so it could and did sync a world the server was
still writing. This was a live bug in shipped behavior, not a Fresh-mode
concern, and it had to be fixed before any of the above could be trusted.

## Decision

Build the world-refresh primitives as **engine- and mode-independent pieces
with no consumer yet**, and make the ordering constraint structural.

- **`saveComplete` means saved.** The companion saves each world with
  `World#save(flush = true)` and answers only after the flushed calls return.
  On the CLI side `saveWorld` returns a tri-state `SaveOutcome`
  (`Saved` / `TimedOut` / `Unreachable`) rather than a Boolean, because the two
  failure modes need opposite handling: a *live* server that was asked to save
  and never confirmed may still be mid-write, so blue-green skips the swap; an
  unreachable companion was never told to save at all, so the on-disk state is
  as consistent as a dead server's and the swap proceeds. Collapsing these into
  one Boolean wedges the loop — the socket does not reconnect within a session,
  so a single dropped connection would make every later rebuild skip forever.

- **`WorldSync` is a two-tier ladder, honest about each rung.**
  macOS clones with `cp -c -R`; Linux with `cp -a --reflink=always`; **Windows
  and everything else use the incremental copy as their supported tier, not as
  an error** — no process is ever exec'd there. The clone lands in a temp
  sibling and swaps in only on success, so a failed clone leaves the previous
  target intact for the incremental fallback to reconcile. A failed clone exec
  latches: the failure mode is "this filesystem cannot reflink," which no retry
  heals, so later syncs skip the doomed exec. Lock files are pruned from the
  cloned tree at every depth, since a clone is a whole-tree replacement and
  "skip" collapses to "must not survive."

- **The refresh is a secondary-world reload over the socket.** `worldRefresh`
  guards the cases that cannot work (blank name; the server's own default
  world, with an error that names the `level-name` constraint; missing
  `level.dat`, meaning the sync has not finished), unloads any previous
  incarnation with `save = false` — the synced files are authoritative, and
  letting the stale incarnation save would overwrite exactly the state the
  refresh exists to replace — parks players at the default spawn so the unload
  can proceed, then loads. `worldWarmup` load-and-unloads a throwaway void
  world to pay the ~0.4 s once-per-JVM JIT cost of the chunk pipeline (gate-5
  finding 5) so the session's first real refresh does not. Failures are always
  *answered*, never silence, including a throwing provider or world API.

- **Ordering is enforced by the type system.** A successful refresh is the only
  source of a `WorldRefreshFlow.RefreshedWorld` token (internal constructor),
  and step 6's host-load entry points take one as a required parameter. The
  §2.1 ordering is then unstateable-in-violation: there is no path to the load
  call without having refreshed first. This is deliberately a compile-time
  guarantee rather than a runtime assertion or a comment, because the failure
  it prevents is silent.

- **Protocol goes to v5 — this reverses ADR-0005's amend-in-place precedent,
  and the reversal is the point.** 0005 amended v4 in place on the grounds that
  an unreleased protocol has no deployed peers to break. That reasoning covers
  *shipping artifacts*, and it is correct about them: both ends ship as one
  jar. It does not cover a **running server**, which is a snapshot of whatever
  version started it — the companion lives in that JVM's memory, and copying a
  new jar to disk does not change it. A rebuilt CLI meeting a still-running
  older companion had both sides claiming v4, passing the strict-equality
  handshake, and then blocking the full 30 s world-op timeout on a request the
  old companion answered with `logger.fine("unknown type")`. The version field
  exists precisely to convert that into the actionable "rebuild your server"
  failure. **Additive-only backward compatibility is a property of matched
  pairs, and the pair is not always matched.**

## Consequences

- Fresh mode gets its world-movement problem solved at filesystem speed where
  the filesystem cooperates, and at a bounded, correct speed where it does not.
  Measured against real Paper 1.21.11: warmup 294 ms, first refresh 79 ms,
  repeat refresh 20 ms.
- **Windows has no CoW tier.** This is a real capability gap, recorded rather
  than hidden: ReFS/Dev Drive block cloning is an open research note, and until
  it is validated, Windows dev loops pay incremental-copy cost. Windows is a
  first-class target here, so this is a debt with a name, not an exclusion.
- **`cp -c` degrades silently on macOS.** Apple's `cp -c` falls back to a real
  copy on non-clonefile filesystems and still exits 0, so "clone unsupported"
  is undetectable there. The cost is bounded at plain-copy — never wrong, only
  slower — but it means the clone tier cannot be *verified* on mac from the
  exit code alone. Linux's `--reflink=always` fails detectably, which is why
  the latch lives on that side.
- **A refreshed world keeps a stable UUID, so the UUID cannot prove a reload.**
  A world directory cloned from a still-loaded world carries that world's
  `uid.dat`, and Bukkit refuses two worlds with one UUID — `createWorld`
  returns null with only a console line. The companion deletes the copy's
  `uid.dat` on a *genuine* collision and keeps it otherwise, so ordinary
  refreshes hold a stable identity. The consequence is that "did this really
  reload?" needed its own answer: `worldReport` carries a `reloaded` flag, set
  when a previous incarnation was found and unloaded. It is the only such proof
  available across the socket.
- **Paper's async chunk I/O outlives `unloadWorld`.** It can write into the
  warmup directory after the unload returns, resurrecting a tree the
  synchronous delete just removed. Deletion is therefore a best-effort
  off-thread retry chain; a directory that survives every retry is harmless and
  the next warmup clears it. Accepting eventual consistency here is cheaper
  than blocking the main thread on a filesystem race.
- **The real-server E2E does not run in CI.** `WorldRefreshE2ETest` is gated
  behind `PPL_E2E=1` because it downloads Paper and boots a JVM. Every one of
  the three findings above was invisible to MockBukkit and surfaced only
  against a live server — so the honest cost of this gating is that the only
  test exercising the real world-load path runs when someone remembers. A
  scheduled run is the obvious follow-up and is not in this change.
- Replay-fixture tests no longer pin `PROTOCOL_VERSION`. A captured fixture
  records the version *its* session ran at; asserting it equals today's
  constant made four tests fail on this bump for reasons unrelated to wire
  shape, and would do so on every future bump. The companion-side replay
  re-stamps the hello's version alongside the auth token it already re-stamps
  — both are per-instance facts, not shape facts — and version negotiation
  keeps its direct coverage in `CompanionClientTest` and
  `CompanionSocketServerTest`.
- Nothing in the dev loop calls any of this yet. Shipping unconsumed
  primitives is a deliberate cost: it buys a real-server integration record and
  a reviewable unit before step 6 has to be correct *and* new at the same time.

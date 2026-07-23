# 0005 — The instant tier: a universal redefine fast lane (protocol v4)

**Status:** Accepted — amended 2026-07-23: the ADDITIVE tier is cut, body-only
everywhere (see the Amendment section; the Decision below is edited to the
amended design, since protocol v4 is unreleased and is amended in place)
**Date:** 2026-07-22, amended 2026-07-23

## Context

In-place class redefinition existed as hot-reload mode's `HOTSWAP` strategy:
a CRC diff on the CLI picked candidate classes, and the companion's
`HotSwapper` redefined them if an ASM pre-check judged the change
method-body-only. Two problems. First, reach: the fastest possible reload —
no unload, no swap, no state loss — was locked to one mode, while restart and
blue-green paid 10–15 s for every one-line body edit. Second, honesty: the
body-only check had silent-staleness blind spots (a changed static
initializer or `onEnable` body passes a structural comparison but never
re-executes), and a change it admitted was reported as swapped with no
verification that the server was even running the bytes the diff assumed.

The classifier's cardinal sin is a false "safe" verdict — silently stale
code, the exact failure this tool positions against. Escalating is cheap
(one normal swap); a wrong patch costs the user a debugging session against
code that was never live.

## Decision

Promote redefinition to a **universal fast lane** that runs in front of
*every* dev mode's rebuild, with a conservative body-only classifier and
verified application.

- **CLI classifies.** Per rebuild the lane compiles (`classes` only — the
  patched path never builds a jar), snapshots the build output, and diffs it
  against the **baseline** — the last output *confirmed loaded* in the live
  server (advanced only on a confirmed full swap or a confirmed patch, per
  class). An ASM `ClassNode` diff grades the change-set:
  - `NONE` — nothing observable changed (debug info only). Report "no code
    changes", do nothing.
  - `BODY_ONLY` — only retained method bodies changed.
  - `UNSAFE` — everything else, each with a named reason. Added and removed
    methods, new classes, and nest-attribute changes are UNSAFE like every
    other structural change *(amended 2026-07-23 — these were the ADDITIVE
    tier, see the Amendment)*.
- **The lifecycle gate is part of UNSAFE**: changes a JVM would happily
  redefine but that can never take effect — new/removed/changed *annotated*
  methods (registration-driven frameworks scan once), `onEnable`/
  `onDisable`/`onLoad` bodies on the plugin main class and `<clinit>` bodies
  (already ran, never rerun), field changes (existing instances keep
  default-initialized state), hierarchy changes, resource edits. The two
  historical blind spots (`<clinit>`, `onEnable`) are now escalations.
- **Capability is per live JVM**, advertised in the socket `welcome`:
  no agent → `NONE`; agent present → `BODY_ONLY`. The lane patches iff the
  change-set is BODY_ONLY and the capability is there; anything structural
  escalates naming the change.
- **Protocol v4** adds an `instantSwap`/`instantReport` message pair,
  mirrored on both ends like the rest of the schema. Class bytes travel on
  the wire (base64 per class) with the expected loaded CRC32 — self-contained
  and container-proof for the Fresh-mode future where the server shares no
  filesystem with the CLI. `LoadRequest.changedClasses` and the `hotswap`
  strategy are deleted outright (clean cut, both ends ship as one artifact,
  same as ADR-0004). *(Amended 2026-07-23: the request carries redefinitions
  only — the `newClasses` list left the wire with the ADDITIVE cut. v4 is
  unreleased, so the shape is amended in place; no v5.)*
- **The companion verifies before applying.** The verification ground truth
  is the agent's **load-hook CRC registry**: the premain registers a
  `ClassFileTransformer` that records the CRC32 of the exact bytes each
  classloader defines, and successful patches are recorded separately.
  (Retransform-based capture was tried first and failed against a live
  server: HotSpot hands retransformers a *reconstituted* class file whose
  constant-pool encoding differs from the loaded original.) Natively loaded
  plugins never match the build CRC literally: Paper's Commodore
  compatibility pass re-encodes every legacy-plugin class between jar and
  `defineClass` — independent of the remap the manifest stamp skips — so the
  define record alone would refuse every native patch as drift (found live:
  restart and blue-green escalated every body edit until this was accounted
  for). A define-record mismatch therefore falls back to the defining
  loader's **raw source bytes** — its own jar/dir entry, read pre-transform
  via `findResource`, where a `URLClassLoader`'s open jar handle serves what
  the loader actually defines from even if the file on disk was replaced —
  and admits iff those match the baseline. A class patched in place must
  match its patch record exactly; its source no longer describes what's
  running, so it never takes the fallback. Any remaining mismatch — CLI
  state drift, a remapped jar, anything — refuses the whole request with the
  reason; the JVM's own
  `UnsupportedOperationException`/`UnmodifiableClassException` remains the
  final backstop. `redefineClasses` is one atomic batch;
  changed-but-unloaded classes are force-loaded (uninitialized) first so a
  jar-backed loader's stale bytes can't resurrect later.
- **LaunchSpec** makes the launch identity structural: one immutable value
  per session (javaBin, the agent, the add-opens) used by every
  server start in every mode and recovery path — no code path can silently
  produce a server the lane can't patch. The gradle-plugin stamps
  `paperweight-mappings-namespace: mojang` into plugin jars so Paper 1.20.5+
  skips its load-time remap, keeping the loader's source jar byte-identical
  to the build output — the source-bytes verification depends on exactly
  that (an unstamped jar gets remapped, its source bytes stop matching the
  baseline, and every patch honestly refuses).
- **Honest reporting is the contract:** every rebuild ends in exactly one of
  "Patched N classes (instant)", "Instant: <named reason> — full swap", or
  "No code changes"; a session-start banner reports the tier ceiling and why
  (`body-only`, `off (…)`); and typing `s`⏎ forces a full swap — the
  user-side reset to ground truth.

## Consequences

- Body-only edits apply in the live
  server in well under a second in every mode — measured ~560 ms
  edit-to-patched including the Gradle compile, ~65 ms for the redefinition
  itself, against a real Paper 1.21.4 server whose running scheduler task
  switched output the tick after the patch landed.
- One residual risk is accepted and documented rather than hidden:
  "new code, old state" (inherent to hotswap of any kind — patched logic
  runs against state computed by old logic). It is bounded by the escape
  hatch and by every escalation being one normal swap away from ground
  truth. *(The second residual — reflection-by-name discovery of added
  members — left with the ADDITIVE cut: body-only patches never change the
  member set.)*
- The companion no longer ships ASM (the classifier moved CLI-side); the
  agent grew from a premain stub to the CRC registry, still dependency-free.
- Refusals surface real environment facts (baseline drift, remapped jars,
  missing add-opens) instead of mispatching — the failure mode is a visible
  fallback, never silence.
- The replay fixtures were re-captured from a real session exercising the
  full arc: fresh load → structural escalation (field) → instant patch →
  lifecycle escalation (`onEnable`) → instant patch → no-change.

## Amendment (2026-07-23) — the ADDITIVE tier is cut

The design as accepted had a third grade, `ADDITIVE` (new classes,
added/removed unannotated methods, nest-attribute changes), patched only
when the live JVM was JBR with `-XX:+AllowEnhancedClassRedefinition`
actually present, and capped back to body-only by a curated list of
scan-once-by-name reflection frameworks. That tier is removed. BODY_ONLY
stays, with all of its verification machinery — the CRC load-hook agent,
the baseline tracker, the escalation gates, and protocol v4 — unchanged.
Added/removed methods and new classes now escalate as UNSAFE with named
reasons. Since protocol v4 is unreleased, the wire shape is amended in
place (the `instantSwap` request loses `newClasses`; the welcome capability
becomes agent-present/absent); there is no v5.

Why the reversal:

1. **The review-cost evidence.** The pre-merge review of this branch found
   twelve ways the tool could silently vouch for stale code, and they
   concentrated worst in structural patching: the new-class define/overlay
   splice, the foreign-loader probe, lifecycle gaps on *added* methods. A
   future classifier gap in that territory fails silent — the exact failure
   this ADR's Context names as the cardinal sin. Body-only's failure
   surface is a fraction of the size, and its failure direction is refusal.
2. **Delivered value was empirically ~zero.** The tier shipped dead for
   Kotlin — `@kotlin.Metadata`'s `d1`/`d2` and `@NotNull` on added methods
   escalated every Kotlin structural change — and nobody noticed until a
   seven-agent review pass. The headline capability never fired for the
   repo's own primary language.
3. **The headline engine can never use it.** CRaC servers run a CRaC JDK,
   not JBR, so the Fresh-mode future is body-only regardless. Post-Fresh,
   an escalation costs a ~3–5 s fresh server (likely less), dissolving the
   escalation-cost asymmetry that justified re-admitting JBR for the
   10–15 s native modes.
4. **The empirical baseline** (`david-local-docs/`
   `jbr-agentic-baseline-experiment.md`, 2026-07-22): a 15-iteration
   agentic plugin build on plain JBR+JDWP hit a hotswap wall in 40% of
   iterations, failed silently or misattributably in 27%, and additive's
   measured marginal value over body-only-plus-fresh-swap was ~12 s per
   session. The same experiment showed JBR 21 *accepts* added methods and
   fields over JDWP while advertising `canAddMethod=false` — capability
   detection is heuristic at the mechanism level, and the safe/unsafe
   boundary for additions is semantic (does any framework observe the
   member set?), which a bytecode classifier cannot decide.

Positioning consequence, recorded so future proposals argue against it:
hotswap tools compete on *when they work*; paperplane competes on *never
being wrong*.

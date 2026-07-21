# 0005 — The instant tier: a universal redefine fast lane (protocol v4)

**Status:** Accepted
**Date:** 2026-07-22

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
*every* dev mode's rebuild, with a capability-tiered conservative classifier
and verified application.

- **CLI classifies.** Per rebuild the lane compiles (`classes` only — the
  patched path never builds a jar), snapshots the build output, and diffs it
  against the **baseline** — the last output *confirmed loaded* in the live
  server (advanced only on a confirmed full swap or a confirmed patch, per
  class). An ASM `ClassNode` diff grades the change-set:
  - `NONE` — nothing observable changed (debug info only). Report "no code
    changes", do nothing.
  - `BODY_ONLY` — only retained method bodies changed.
  - `ADDITIVE` — additionally new classes and/or added/removed unannotated
    methods (includes nest-attribute changes from new anonymous classes).
  - `UNSAFE` — everything else, each with a named reason.
- **The lifecycle gate is part of UNSAFE**: changes a JVM would happily
  redefine but that can never take effect — new/removed/changed *annotated*
  methods (registration-driven frameworks scan once), `onEnable`/
  `onDisable`/`onLoad` bodies on the plugin main class and `<clinit>` bodies
  (already ran, never rerun), field changes (existing instances keep
  default-initialized state), hierarchy changes, resource edits. The two
  historical blind spots (`<clinit>`, `onEnable`) are now escalations.
- **Capability is per live JVM**, advertised in the socket `welcome`:
  no agent → `NONE`; agent on a stock JVM → `BODY_ONLY`; agent on JBR with
  the `-XX:+AllowEnhancedClassRedefinition` launch flag actually present →
  `ADDITIVE` (vendor-only detection over-reported). The lane patches iff
  requirement ≤ capability; a shortfall escalates naming the structural
  change and the missing runtime. A curated list of scan-once-by-name
  reflection frameworks caps `ADDITIVE` back to `BODY_ONLY` when present.
- **Protocol v4** adds an `instantSwap`/`instantReport` message pair,
  mirrored on both ends like the rest of the schema. Class bytes travel on
  the wire (base64 per class) with the expected loaded CRC32 — self-contained
  and container-proof for the Fresh-mode future where the server shares no
  filesystem with the CLI. `LoadRequest.changedClasses` and the `hotswap`
  strategy are deleted outright (clean cut, both ends ship as one artifact,
  same as ADR-0004).
- **The companion verifies before applying.** The verification ground truth
  is the agent's **load-hook CRC registry**: the premain registers a
  `ClassFileTransformer` that records the CRC32 of the exact bytes each
  classloader defines, and successful patches update it. (Retransform-based
  capture was tried first and failed against a live server: HotSpot hands
  retransformers a *reconstituted* class file whose constant-pool encoding
  differs from the loaded original.) A CRC mismatch — CLI state drift, a
  remapped jar, anything — refuses the whole request with the reason; the
  JVM's own `UnsupportedOperationException`/`UnmodifiableClassException`
  remains the final backstop. `redefineClasses` is one atomic batch;
  changed-but-unloaded classes are force-loaded (uninitialized) first so a
  jar-backed loader's stale bytes can't resurrect later.
- **New classes** load through the plugin's own loader: directory-backed dev
  loaders see them on disk; `DevPluginClassLoader` can define them from wire
  bytes; Paper's jar-backed `PluginClassLoader` gets an overlay-dir
  `addURL` splice (the server JVM always launches with
  `--add-opens java.base/java.net=ALL-UNNAMED` for this). Unspliceable
  loaders refuse → escalate.
- **LaunchSpec** makes the launch identity structural: one immutable value
  per session (javaBin, JBR flags, the agent, the add-opens) used by every
  server start in every mode and recovery path — no code path can silently
  produce a server the lane can't patch. The gradle-plugin stamps
  `paperweight-mappings-namespace: mojang` into plugin jars so Paper 1.20.5+
  skips its load-time remap and native-mode loaded bytes stay byte-identical
  to the build output.
- **Honest reporting is the contract:** every rebuild ends in exactly one of
  "Patched N classes (instant)", "Instant: <named reason> — full swap", or
  "No code changes"; a session-start banner reports the tier ceiling and why
  (`additive (JBR)`, `body-only`, `off (…)`); and typing `s`⏎ forces a full
  swap — the user-side reset to ground truth.

## Consequences

- Body-only edits (and, on JBR, additive-structural ones) apply in the live
  server in well under a second in every mode — measured ~560 ms
  edit-to-patched including the Gradle compile, ~65 ms for the redefinition
  itself, against a real Paper 1.21.4 server whose running scheduler task
  switched output the tick after the patch landed.
- Two residual risks are accepted and documented rather than hidden:
  "new code, old state" (inherent to hotswap of any kind — patched logic
  runs against state computed by old logic) and reflection-by-name discovery
  outside the curated cap list. Both are bounded by the escape hatch and by
  every escalation being one normal swap away from ground truth.
- The companion no longer ships ASM (the classifier moved CLI-side); the
  agent grew from a premain stub to the CRC registry, still dependency-free.
- Refusals surface real environment facts (baseline drift, remapped jars,
  missing add-opens) instead of mispatching — the failure mode is a visible
  fallback, never silence.
- The replay fixtures were re-captured from a real session exercising the
  full arc: fresh load → structural escalation (field) → instant patch →
  lifecycle escalation (`onEnable`) → instant patch → no-change.

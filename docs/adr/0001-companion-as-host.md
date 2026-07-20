# 0001 — Companion-as-host plugin loading

**Status:** Accepted
**Date:** 2026-07-20

## Context

Hot-reload mode needs to load, unload, and reload the user's plugin inside a
running Paper server. Vanilla Paper has no supported API for this: plugins are
discovered once at boot, and `PluginClassLoader` is internal and
version-specific.

The first-generation approach ASM-patched the user's `JavaPlugin` bytecode at
load time (`JavaPluginPatcher`) so its constructor would tolerate being
instantiated outside Paper's loader machinery. This worked but was fragile:
bytecode surgery against an unstable internal contract, invisible to the
compiler, and hard to reason about when Paper internals shifted.

Separately, Paper's `JavaPlugin` no-arg constructor contains a public
integration point: if `getClassLoader()` is a `ConfiguredPluginClassLoader`
(a Paper interface, 1.19.3+), the constructor calls `init(this)` on the
loader — the same path Paper's own loader uses.

## Decision

The companion plugin **hosts** the user's plugin rather than teaching Paper to
load it:

- The user's plugin never enters `plugins/`. It is staged in
  `.paperplane/staged/` and loaded by the companion on request. Paper's own
  scanner only ever sees the companion.
- `DevPluginClassLoader` extends `URLClassLoader` and **implements
  `ConfiguredPluginClassLoader`**, so Paper's *unpatched* `JavaPlugin`
  constructor initializes the dev-loaded plugin through a compile-time-checked
  interface. The ASM patching path is deleted.
- The loader is **child-first** (new build output must shadow the old classes
  visible through Paper's shared parent) and provides cross-plugin visibility
  by falling back to other plugins' classloaders, with a re-entrancy guard
  against loader cycles.
- `InnerPluginHost` owns the full lifecycle: validate `plugin.yml` (rejecting
  shapes that cannot load late, e.g. `load: STARTUP`), build the loader,
  instantiate, register the plugin in the manager's lookup maps so
  cross-plugin `getPlugin(name)` works, apply command/permission diffs via
  public-API registrars, then `enablePlugin` so events fire correctly.
  Reload is teardown + load.
- The host's entire reflection footprint is centralized in `ReflectionProbe`,
  which runs guards once at first use and refuses to operate
  (`UnsupportedPaperVersionException`) rather than degrade silently when a
  Paper version breaks an assumption.
- **Mode separation:** only hot-reload uses the host. Restart and blue-green
  modes deploy natively to `plugins/` — when a fresh server boot is happening
  anyway, native loading has full fidelity and the host would only subtract
  from it.

## Consequences

- Plugin initialization rides a public Paper interface instead of bytecode
  surgery; a Paper change surfaces as a probe failure with a clear error, not
  undefined behavior.
- The host can only do what a *late* load can do: bootstrap/`load: STARTUP`
  plugins, registry modification, and worldgen are rejected up front with a
  categorical cause instead of failing mysteriously.
- Everything Paper does for natively-loaded plugins beyond classloading —
  command registration, help topics, Brigadier lifecycle commands,
  permissions — must be replicated (and un-replicated on teardown) by the
  host. This is the bulk of the host code and its test surface.
- Unloading remains fundamentally best-effort on the JVM; see ADR-0003 for
  how leaks are contained rather than denied.

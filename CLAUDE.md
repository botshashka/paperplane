# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

PaperPlane (`ppl`) is a CLI dev tool for Minecraft Paper plugin development. It automates the build-run-test cycle: watches source files, rebuilds via Gradle, manages Paper server instances, and hot-deploys plugins — all with a single `ppl dev` command.

## Build & Test

```bash
# Build the CLI fat jar (includes companion + velocity plugins as embedded resources)
./gradlew :cli:shadowJar

# Run the CLI locally
./ppl dev            # start dev server with file watching
./ppl create         # scaffold a new Paper plugin project (alias: ppl new)
./ppl init           # add PaperPlane to an existing project (alias: ppl setup)
./ppl clean          # clean .paperplane directory

# Run tests
./gradlew test                        # all tests
./gradlew :cli:test                   # CLI tests only
./gradlew :cli:test --tests "dev.paperplane.cli.server.PaperServerManagerTest"  # single test class

# Build individual modules
./gradlew :companion-plugin:shadowJar
./gradlew :velocity-plugin:shadowJar
./gradlew :gradle-plugin:jar
```

## Architecture

Five Gradle submodules (`settings.gradle.kts`):

- **`cli`** — The `ppl` command. Kotlin + Clikt CLI framework. Embeds companion and velocity plugin jars as resources (copied as `.bin` files during build to prevent Shadow from unpacking them). Uses Gradle Tooling API (`GradleBridge`) to invoke builds in the user's project without shelling out.

- **`gradle-plugin`** — Applied to user projects (`dev.paperplane`). Auto-generates `plugin.yml` from a `paperplane {}` extension block, detects Paper API version from dependencies, and produces `metadata.json` (`ppMetadata` task) consumed by the CLI.

- **`companion-plugin`** — Bukkit plugin injected into the dev server at runtime. Provides: build status bar (action bar overlay), error catching/display, auto-op for joining players, save protection during rebuilds, hot-reload orchestration (unload/load plugin JARs), and server-ready signaling via flag files.

- **`velocity-plugin`** — Velocity proxy plugin for blue/green mode. Polls `active-server.json` to route/transfer players between blue and green Paper backends during zero-downtime rebuilds.

- **`agent`** — Minimal Java agent for hot-swap class redefinition. Produces a JAR with `Premain-Class` manifest for `Can-Redefine-Classes` / `Can-Retransform-Classes`.

### Dev Server Modes

Configured via `dev.mode` in `paperplane.yml`:

1. **Restart** (`mode: restart`) — Stops server, rebuilds, restarts. Simple but has downtime.
2. **Blue-green** (`mode: blue-green`) — Two Paper servers behind a Velocity proxy. Rebuilds deploy to the standby server, then players are transferred. Pre-warms the standby after each swap.
3. **Hot-reload** (`mode: hot-reload`) — Single server stays running. Companion plugin unloads/reloads the plugin JAR in-place. Fastest iteration but experimental.

### CLI ↔ Server Communication

The CLI and companion plugin coordinate through flag files in `.paperplane/` inside the server directory:
- `server-ready` — companion writes after `ServerLoadEvent`
- `companion-status.json` — CLI writes build state, companion reads for action bar
- `save-complete` — companion writes after world save
- `reload-complete` / `reload-failed` — companion writes after hot-reload attempt

### TerminalUI Block System

All CLI output goes through `TerminalUI` (`cli/.../ui/TerminalUI.kt`). Spacing between sections is handled by **scoped blocks**, not manual `blank()` or `println()` calls.

**Two scoped primitives:**
- `TerminalUI.block { … }` — one-shot PERSIST block for command output. The lambda receiver is `TerminalUI`, so emit calls (`success(...)`, `info(...)`, `error(...)`) are unqualified. The block closes in a finally, so exceptions can't leak a pinned footer.
- `TerminalUI.phase { … PhaseEnd.Watching }` — iteration-scoped block for dev-server loops. Discards any prior pinned footer, opens a PERSIST block, runs the body, then opens a trailing TRANSIENT footer based on the returned `PhaseEnd`:
  - `PhaseEnd.Watching` → "Watching for changes..."
  - `PhaseEnd.Waiting` → "Waiting for changes..." (build/server failure)
  - `PhaseEnd.None` → no trailing footer (terminal exit)

**Rules:**
- Every block automatically gets 1 blank line above it. To add space between two groups of output, use two `block { }` calls — don't insert `blank()` between them.
- `blank()` is only for intra-block spacing (visual grouping within a single block).
- `serverLog()` interleaves log lines above the pinned footer; safe to call from inside or outside a block/phase.
- `spin(msg) { … }` pins a spinner. Inside a `block { }` / `phase { }` it reuses the outer block; outside, it auto-opens one.
- `TerminalUI.clearPinnedFooter()` clears any pinned footer. Only needed in shutdown hooks as a safety net.
- Deep helpers (`GradleBridge`, `PaperServerManager`, etc.) call typed emit methods (`status`, `error`, `buildError`, `serverLog`) directly — they inherit the current block from whatever phase the command opened.
- `beginBlock` / `endBlock` / `discardBlock` are `@PublishedApi internal` implementation details of `block { }` and `phase { }`. Don't call them directly.

## Commit Convention

Use [Conventional Commits](https://www.conventionalcommits.org/). Format: `<type>: <description>`

Types: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `chore`, `ci`, `build`

## Key Conventions

- Kotlin throughout, Java 21 toolchain, JUnit 6 for tests
- Config is `paperplane.yml` (parsed with kaml/kotlinx-serialization) — see `PaperPlaneConfig.kt` for schema
- The `ppl` launcher script at repo root runs the built CLI jar directly
- Version catalog in `gradle/libs.versions.toml` — all dependency versions managed there

## Testing Standards

**Aim for 100% coverage on every new module.** Don't declare a feature done until you've audited what's tested. Before claiming completion, walk every public function, every error branch, and every edge case and verify each is exercised by a test. If something isn't covered, either add a test or have a written reason why coverage isn't worth it (e.g. "thin Clikt wrapper around already-tested logic"). Coverage gaps are a defect, not a tradeoff to defer. The phrase "the tests cover the core" is a smell — list what's NOT covered and decide explicitly.

**External-API clients must be tested against captured real responses, not hand-rolled fixtures.** When testing code that parses output from an external service (Modrinth, Hangar, GitHub releases, Paper API, etc.), the JSON/XML/whatever fixture must come from a real `curl` against the live endpoint, pasted verbatim into the test. Hand-rolled "looks-about-right" fixtures test your assumptions in lockstep with your parser — both can be wrong in the same direction and the test will still pass. Real example: an early `ModrinthClient` parser required `hashes.sha256`, and every test fed it `{"sha256": "..."}` JSON and passed, while real Modrinth responses contain only `sha1` and `sha512` — the entire integration was broken in production despite green tests. Lock the real shape in with at least one golden test per external endpoint, named so it's obviously the source of truth (e.g. `parses real Modrinth response shape`). Hand-rolled fixtures are still fine for edge cases (missing fields, error responses) — but the happy path must be a real captured payload.

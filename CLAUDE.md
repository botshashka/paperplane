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
./ppl init           # scaffold a new Paper plugin project
./ppl setup          # download/configure Paper server
./ppl test           # run tests via Gradle Tooling API
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

Four Gradle submodules (`settings.gradle.kts`):

- **`cli`** — The `ppl` command. Kotlin + Clikt CLI framework. Embeds companion and velocity plugin jars as resources (copied as `.bin` files during build to prevent Shadow from unpacking them). Uses Gradle Tooling API (`GradleBridge`) to invoke builds in the user's project without shelling out.

- **`gradle-plugin`** — Applied to user projects (`dev.paperplane`). Auto-generates `plugin.yml` from a `paperplane {}` extension block, detects Paper API version from dependencies, and produces `metadata.json` (`ppMetadata` task) consumed by the CLI.

- **`companion-plugin`** — Bukkit plugin injected into the dev server at runtime. Provides: build status bar (action bar overlay), error catching/display, auto-op for joining players, save protection during rebuilds, hot-reload orchestration (unload/load plugin JARs), and server-ready signaling via flag files.

- **`velocity-plugin`** — Velocity proxy plugin for blue/green mode. Polls `active-server.json` to route/transfer players between blue and green Paper backends during zero-downtime rebuilds.

### Dev Server Modes

Configured via `paperplane.yml` in the user's project:

1. **Single server** (`proxy: false`) — Stops server, rebuilds, restarts. Simple but has downtime.
2. **Blue/green** (`proxy: true`, default) — Two Paper servers behind a Velocity proxy. Rebuilds deploy to the standby server, then players are transferred. Pre-warms the standby after each swap.
3. **Hot-reload** (`hot-reload: true`) — Single server stays running. Companion plugin unloads/reloads the plugin JAR in-place. Fastest iteration but experimental.

### CLI ↔ Server Communication

The CLI and companion plugin coordinate through flag files in `.paperplane/` inside the server directory:
- `server-ready` — companion writes after `ServerLoadEvent`
- `companion-status.json` — CLI writes build state, companion reads for action bar
- `save-complete` — companion writes after world save
- `reload-complete` / `reload-failed` — companion writes after hot-reload attempt

## Commit Convention

Use [Conventional Commits](https://www.conventionalcommits.org/). Format: `<type>: <description>`

Types: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `chore`, `ci`, `build`

## Key Conventions

- Kotlin throughout, Java 21 toolchain, JUnit 5 for tests
- Config is `paperplane.yml` (parsed with kaml/kotlinx-serialization) — see `PaperPlaneConfig.kt` for schema
- The `ppl` launcher script at repo root runs the built CLI jar directly
- Version catalog in `gradle/libs.versions.toml` — all dependency versions managed there

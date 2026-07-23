# Architecture Decision Records

Short, dated records of the significant architectural decisions in paperplane:
what was decided, what the context was, and what the consequences are.

ADRs are immutable history. When a decision changes, the old ADR is not
rewritten — a new ADR supersedes it and the old one gets a `Superseded by`
note in its status line. This keeps the *why* behind the code discoverable
even after the code moves on.

## Index

- [0001 — Companion-as-host plugin loading](0001-companion-as-host.md)
- [0002 — File-based JSON load protocol](0002-file-based-json-load-protocol.md) *(superseded by 0004)*
- [0003 — Leak containment over leak prevention](0003-leak-containment.md)
- [0004 — Localhost TCP socket transport for the CLI↔companion protocol](0004-socket-transport.md)
- [0005 — The instant tier: a universal redefine fast lane (protocol v4)](0005-instant-fast-lane.md)

## Format

Each ADR has: **Status** (Proposed / Accepted / Superseded by NNNN), **Date**,
**Context** (the forces at play), **Decision** (what we chose), and
**Consequences** (what follows, good and bad — including the honest costs).

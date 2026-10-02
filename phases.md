# Phases

Where the project has been, where it is now, and what is explicitly *not*
promised yet.

## Completed

### Phase 1 — Foundation
- Fabric client mod skeleton on Minecraft 26.1.2 (Java 25, no intermediary
  mappings — 26.1 ships unobfuscated).
- `fabric.mod.json`, icon, language file, `Branding` constants.
- `#chika_build` wired to the engine's command registry.

### Phase 2 — The build pipeline
- `SchematicLocator` against `.minecraft/schematics/` (case-insensitive,
  optional extension, path-escape rejected).
- `BuildCoordinator` with a fixed sequence and three outcomes
  (`STARTED` / `PAUSED` / `REJECTED`).
- `MaterialPlanner` priority ladder: already-placed → inventory → creative →
  shop → missing.
- `BuildService` / `SchematicAnalyzer` / `PlayerContext` seams, implemented in
  `platform/engine`.

### Phase 3 — Honest supply
- `PurchaseOrchestrator` buys only the shortfall and verifies items arrived.
- `ShopRegistry` with pluggable, server-specific adapters (ships empty).
- Pause → gather → resume, without rebuilding correct blocks.
- Creative never changes the gamemode and reports mismatches.

### Phase 4 — Command lockdown & branding
- Every engine command unregistered except `chika_build` + `chika_builder`,
  re-swept for the first 20 seconds.
- `#chika_builder creative|shop true|false` + persistence in
  `config/chika-builder.json`.
- Settings screen (unbound key), D Web Studio watermark (default ON, F1-safe).

### Phase 5 — Product hardening (this release)
- Engine merged into a single jar: one Mods-screen entry, one file to install.
- Stale/contradictory comments and metadata corrected; `LICENSE` added.
- Full documentation set: README, architecture, ai-loop, memory, design,
  phases, prd, rules, CONTRIBUTING, CHANGELOG.
- `.gitignore` anchored correctly (an unanchored `build/` had been hiding the
  whole `builder/build/` package) and extended with secret patterns.
- Test suite green (see CHANGELOG for the shipped set).

### Phase 6 — Verified Creative supply (unreleased)
- Creative is a real supply rung, not an assumption: the missing blocks are
  handed into the inventory through the game's own Creative hand-over and the
  inventory is re-counted afterwards.
- A hand-over that delivers nothing, or only part of the shortfall, is reported
  and the build pauses on the remainder. The gamemode is still only ever read.
- `#chika_builder creative <TAB>` completes `true` / `false` again — the
  `args.has(1)` regression that hid the values is fixed and test-covered.

## Current

**v1.1.2 released: runtime recovery, branding, and a strict no-partial-resume
supply loop.** The full loop works end to end: `#chika_build castle.schematic` →
locate → parse → compare with world → plan supply → hand over from Creative
and/or buy from the shop → place → verify → stop cleanly. What the live tests
drove in: a per-tick build supervisor that recovers the engine's own pauses,
`[Chika Builder]` chat branding, and the rule that the engine may only resume
when a re-plan against the live inventory shows **every** material requirement
covered. All of it is test-covered; the live in-game run of v1.1.2 is the next
milestone.

## Future (ideas, not commitments)

Nothing below is implemented, scheduled, or covered by tests. It is listed so
the gap is visible rather than implied:

- Shop adapters for specific servers (the extension point exists; no adapter
  ships).
- In-chat build progress reporting (does not exist today — outcomes are
  start/pause/reject only).
- Additional schematic formats beyond `.schematic` / `.litematic`.
- A standalone build backend replacing the current engine.

If a future release implements any of these, it moves to **Completed** with a
CHANGELOG entry — nothing here should be documented as working before that.

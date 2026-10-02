# Product Requirements

**Chika Builder** — a Minecraft schematic building mod by **D Web Studio**.

## Problem

Building a large structure block-by-block is slow, and the tools that automate
it expose a large surface of movement/mining/follow commands most players never
asked for. Chika Builder does one job: it builds a schematic you point it at.

## Goals

1. One command builds a file: `#chika_build <filename>.schematic`.
2. Honest outcomes — the player always learns what actually happened.
3. Beginner-simple: install one jar, drop files in `schematics/`, type.
4. Nothing else reachable: the rest of the engine's command surface is gone.

## Non-goals

- No movement, mining, following, exploration or waypoint commands.
- No game-mode changes, ever.
- No packet manipulation, anti-cheat bypasses, or server-side exploits.
- No automatic spending unless the player opts in **and** a shop exists.
- Not a world-editing or structure-copying tool.

## Functional requirements

| # | Requirement | Verified by |
|---|---|---|
| FR-1 | Resolve `.schematic` / `.litematic` names against `.minecraft/schematics/`, case-insensitively, extension optional | `SchematicLocator` (see limitations) |
| FR-2 | Reject names containing `/`, `\`, `..` so nothing outside the folder is reachable | `SchematicLocator` |
| FR-3 | Create `schematics/` on first start | `ChikaBuilderClient` (`mkdirs()`) |
| FR-4 | Parse the schematic, derive required blocks, anchor at the player's feet | `SchematicAnalyzer` |
| FR-5 | Skip blocks already correct in the world — never re-bought, never re-placed | `BuildCoordinatorTest.resumeDoesNotRebuildBlocksThatAreAlreadyCorrect` |
| FR-6 | Supply ladder: already-placed → inventory → creative → shop → pause | `MaterialPlannerTest.planIsOrderedByThePriorityLadder` |
| FR-7 | Creative applies only when the player is *actually* in Creative, never changes the gamemode, and only counts a hand-over the inventory confirms | `BuildCoordinatorTest.creativeWorksOnlyWhenThePlayerIsActuallyInCreative`, `.aCreativeHandoverThatDeliversNothingPausesTheBuild`, `.aPartialHandoverPausesAndReportsTheShortfall`, `CreativeAcquisitionTest` |
| FR-8 | `creative` / `shop` settings persist across sessions | `ChikaConfigTest` |
| FR-9 | Pause with an exact list of what is missing | `BuildCoordinatorTest.missingMaterialsPauseAndReportExactlyWhatIsMissing` |
| FR-10 | Resume from where it stopped, without redoing finished blocks | `BuildCoordinatorTest.aPausedBuildResumesOnceTheItemsArrive` |
| FR-11 | Buy only the shortfall, then verify the items arrived | `PurchaseOrchestratorTest.purchasesOnlyTheShortfall`, `.aPurchaseIsRejectedWhenTheItemsNeverArrive` |
| FR-12 | Insufficient money / unknown item / unavailable shop / no adapter → pause, never a fake success | `PurchaseOrchestratorTest`, `ShopRegistryTest` |
| FR-13 | Unreadable schematic → clear rejection, no crash | `BuildCoordinatorTest.anUnreadableSchematicIsRejectedRatherThanCrashing` |
| FR-14 | Exactly two commands registered; every other engine command removed | `CommandLockdownTest` |
| FR-15 | Player-facing metadata never names the engine; one Mods-screen entry | `SingleModEntryTest` |
| FR-16 | Icon shown instead of a `?` | `ModIconTest` |

## UX requirements

- Every outcome is reported in chat in plain language (start / pause / reject).
- A pause must tell the player the remedy: gather the items, run the same
  command again.
- Settings are available both as `#chika_builder …` and from a settings screen
  with a keybind that ships unbound.
- Tab completion is trustworthy: `#chika_builder ` offers only the setting names,
  and `#chika_builder creative ` offers only the values the command accepts
  (`true`/`false`), filtered by whatever has been typed so far.
- Defaults are safe: watermark on, creative **off**, shop **off**.

## Quality requirements

- `.\gradlew.bat build` succeeds from a clean checkout with the engine jar in
  `local/`.
- The full JUnit suite passes with zero failures.
- No secrets, credentials or generated artifacts in the repository.
- User-facing text is free of engine branding.

## Out-of-scope for v1.0.0

See [phases.md](phases.md) — future ideas are listed there and are explicitly
not implemented.

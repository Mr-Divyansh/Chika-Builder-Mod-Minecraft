# Changelog

All notable changes to Chika Builder.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions are tagged with the mod version in `gradle.properties`.

## [1.1.2] — Creative supply never resumes a half-supplied build

### Fixed

- **A partial Creative hand-over no longer resumes the engine.** A live test
  showed Creative delivering 191 of 248 blocks, the build being resumed anyway,
  and then reporting `57 block(s) still missing`. The resume condition was
  simply "some blocks arrived". It is now strictly "blocks arrived **and** a
  re-plan against the live inventory shows every requirement covered" - so a
  partial round (191 of 248, or one material type complete while another is
  still short) leaves the engine paused and triggers another supply round.
  Resume becomes possible only when the live inventory itself confirms the
  requirement, across all material types.
- **The supply seam now reports satisfaction, not just a count.**
  `BuildSupervisor.Supply` returns a `SupplyResult(delivered, satisfied)`, and
  `BuildCoordinator.supplyOutstanding()` decides `satisfied` by re-planning from
  the real inventory after every round, never by the delivery claim.
- **The paused report no longer promises a resume while material is missing.**
  It says `Creative supply incomplete.`, then one `Missing: Dirt x57` line per
  still-short material straight from the inventory, then
  `Build remains paused - N block(s) still missing.` The old
  "Running #chika_build again resumes." line is gone from that path; the
  detailed reasons move to the log.

### Changed

- Safety stays bounded and is now precise: rounds that deliver nothing count
  against the retry budget (`MAX_SUPPLY_ATTEMPTS = 5`) and end in a paused
  report with the exact remainder; rounds that make real progress reset only
  that no-progress budget, so a build that is still closing a gap is never
  abandoned and never spins forever. Creative still requires the player to be
  genuinely in Creative - the setting is never a substitute for it, and the
  gamemode is never changed.
- Version bumped to 1.1.2 (Minecraft 26.1.2 unchanged).

## [1.1.1] — live runtime recovery and branding

### Added

- **Build supervisor.** The engine stops on its own when it runs out of blocks
  (`Missing materials for at least:` / `Unable to do it. Pausing.`) - and its
  `#resume` command is removed by the command lockdown, so the build used to sit
  paused forever. A per-tick supervisor now watches the running build, supplies
  what is missing, resumes the engine, and only reports `PAUSED` (with the exact
  shortfall) after a bounded number of fruitless supply rounds. Completion is
  verified against the world, never taken from the engine stopping.
- **Mid-build Creative top-ups.** `#chika_build` supplies materials up front;
  the supervisor re-plans from the live world whenever the engine pauses, so a
  build that runs dry mid-way is handed the missing blocks and continues instead
  of stopping.
- **Creative supply loops to the full shortfall.** A single hand-over fills at
  most one stack, so asking once for 248 stone could only ever produce 64. The
  acquisition now repeats - bounded by the shortfall itself - until the whole
  amount arrives, the inventory is full, or a round makes no progress.
- **`[Chika Builder]` chat branding.** Every engine line is delivered through
  the engine's own `Settings.logger` sink (verified as the engine's *only* path
  to Minecraft chat). That sink is wrapped at startup: the engine's
  `[Baritone]` tag is stripped and the line is re-emitted with the pink
  `[Chika Builder]` prefix. The third-party jar is not modified. The engine's
  desktop notification helper is disabled so the engine's name cannot appear in
  an OS toast either.
- **State-aware "already built" detection.** Correct blocks are compared with
  their full block state, not just their block type, so a wrongly oriented stair
  stays in the remaining work set instead of being counted as done and then
  reported as missing material.
- **Production diagnostics.** The log now prints, per material, the exact
  required / already-placed / held / outstanding / shortfall counts at plan
  time, and for every Creative hand-over the item id, amount requested,
  inventory before, amount supplied, inventory after and amount remaining -
  so a live failure can be read straight out of `logs/latest.log`.

### Fixed

- **Creative hand-overs now use vanilla's slot numbering.** The server accepts
  a creative slot packet only for `InventoryMenu` slots 1..45; we were sending
  raw container indices, so hotbar hand-overs were silently dropped (slot 0) or
  written into the crafting/armor slots (1-8). The supplier now maps container
  index to menu slot exactly the way the vanilla Creative screen does, so the
  server's inventory matches the client's.
- **Plan, supplier and engine now read the same 36 inventory slots.** The
  engine scans only the 36 hotbar/main rows; the plan counted the off-hand too
  and the supplier could write into it. Blocks sitting in the off-hand are no
  longer counted as usable and no longer handed over into it.

### Changed

- Version bumped to 1.1.1 (Minecraft 26.1.2 unchanged).

## [Unreleased]

### Added

- **Creative supply is real, and verified.** The Creative rung no longer treats
  "Creative will supply this" as satisfied: it hands the missing blocks into the
  player's inventory and re-counts the inventory afterwards, so a hand-over only
  counts when the items genuinely arrived. A partial hand-over (a full inventory,
  say) is reported as such and the build pauses on the remainder. The gamemode is
  still only ever *read*, never changed, and the fact is re-checked immediately
  before every hand-over.

### Fixed

- **`#chika_builder creative <TAB>` now completes `true` / `false`.** Completion
  used `args.has(1)`, which is already true while the *value* is being typed, so
  only the setting names were ever offered. It now tests the exact argument count
  and filters the value against the argument under the cursor, so `creative t`
  completes to `true` and `creative f` to `false`.

## [1.0.0] — initial public release

### Added

- **`#chika_build <filename>.schematic`** — builds a schematic from
  `.minecraft/schematics/` with its corner at the player's feet. Supports
  `.schematic` and `.litematic`, optional extension, case-insensitive names,
  and Tab completion.
- **`#chika_builder creative|shop true|false`** — persisted settings, plus a
  settings screen bound to a keybind that ships unbound.
- **Pause and resume** — a build that cannot be supplied pauses with the exact
  missing blocks; re-running the same command continues without rebuilding
  what is already correct.
- **Supply ladder** — already-placed → inventory → Creative → shop → pause.
- **Creative safety** — applies only when the player is genuinely in Creative
  and never changes the game mode.
- **Auto-shop orchestration** — buys only the shortfall and verifies the items
  arrived; ships with **no adapter** (server `/shop` implementations differ) and
  pauses honestly when none is registered.
- **Command lockdown** — every engine command is unregistered except the two
  above, re-swept for the first 20 seconds of a session.
- **D Web Studio branding** — settings screen, optional corner watermark
  (default on, hidden with F1), and mod icon.

### Changed

- The internal build engine is now **merged into the Chika Builder jar**, so
  installation is a single file and the Mods screen shows only Chika Builder.
- `.gitignore` build rule anchored to `/build/` — the previous unanchored
  `build/` pattern was hiding `src/main/java/dev/chika/builder/build/` from
  version control.
- Metadata, javadoc and build-script comments corrected where they still
  described the two-command surface as "one command" or the engine as a
  separate install.
- README rewritten as a user-facing guide (requirements, install, schematic
  folder, commands, limitations, troubleshooting).
- Full documentation set added: `architecture.md`, `ai-loop.md`, `memory.md`,
  `design.md`, `phases.md`, `prd.md`, `rules.md`, `CONTRIBUTING.md`.
- Project-owned engine wrapper renamed to Chika Builder terminology: the
  `platform.baritone` package is now `platform.engine`, and
  `BaritoneBuildService` / `BaritoneSchematicAnalyzer` are now
  `ChikaBuildService` / `ChikaSchematicAnalyzer`. Remaining engine-name
  references are only the third-party API imports, the engine jar filename,
  and the test guards that assert the engine never surfaces to players.

### Security

- `.gitignore` extended with secret patterns (`.env`, keys, tokens,
  credentials, local configuration). No credentials were ever committed.

### Known limitations

- No shop adapter ships; auto-shop requires a server-specific adapter.
- Minecraft 26.1.2 only.
- Unreferenced 2 MB screenshot removed from the repository root.

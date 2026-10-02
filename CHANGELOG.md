# Changelog

All notable changes to Chika Builder.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions are tagged with the mod version in `gradle.properties`.

## [1.1.2] — The movement freeze: real root cause, verified against a live log

### Fixed

- **The build no longer freezes on `buildIgnoreExisting = false`.** The previous
  report blamed a "stale path" and shipped a pause/resume watchdog. A live log
  from the reported failure (FastClient profile `26-1-2`, 16:28–16:30) proves
  that diagnosis was wrong, and shows the real cause:

  ```
  16:28:57  goal=JankyGoalComposite remaining=52
            lastMovementTick=686 lastProgressTick=662
  16:29:07  goal=JankyGoalComposite remaining=52
            lastMovementTick=686 lastProgressTick=662
  16:29:17  goal=JankyGoalComposite remaining=52
            lastMovementTick=686 lastProgressTick=662
  ```

  The watchdog *did* fire, three times, and nothing ever changed — not the
  player position, not the block count, not the goal.

  Reading `BuilderProcess.onTick`'s bytecode gives the actual mechanism. With
  `buildIgnoreExisting = false`, a schematic cell whose world block is non-air
  but does not match is **not** skipped: the engine builds a `GoalBreak` for it
  and wraps it with the placement goal in a `JankyGoalComposite` (the two
  `new JankyGoalComposite` sites in `onTick`), then tries to **mine** that
  block. If the player cannot reach or break it, the engine re-derives the
  identical goal every tick, never places anything and never pauses — while
  `isActive()` stays `true` because it is just `schematic != null`. The build
  looks alive and does nothing. Now set to **`true`**, a supported engine
  setting, so existing blocks are left alone and no mining goal is emitted.

- **A recovery is no longer reported as successful unless it was.** The old code
  returned "recovered" the instant the engine accepted the pause, which is why
  the live log printed *"progress resumed after repath"* three times on a build
  that never moved. There is now a distinct `REPATH_STARTED` outcome and a
  verification window: success requires the player to have moved, a block to
  have been placed, or the engine to have genuinely re-targeted. An unchanged
  goal is recorded as a **failed** attempt and consumes the bounded budget.
  Chat stays silent until a recovery is actually verified.

- **The engine's goal class name is no longer treated as a progress signal.**
  `JankyGoalComposite` was identical on every one of the three failed
  recoveries, so it could never distinguish "re-planned" from "handed back the
  same target". Diagnostics now also read the goal's `toString()` and the
  current path's destination, both of which embed the actual target.

- **2,367 redundant schematic re-analyses per two minutes are gone.** The live
  log contained 16,569 lines of `Schematic materials` / `Material resolved`
  output: the watchdog read the remaining-block count every client tick and each
  read re-parsed the file and logged seven lines. The count is now cached for
  one second (`BuildCoordinator.OUTSTANDING_REFRESH_TICKS`) with an explicit
  `invalidateOutstanding()`, and the material tally is logged once per file
  instead of once per analysis.

- **Fabric no longer warns about invalid mod json entries.** `description_marker`
  is not a supported root entry (`Unsupported root entry "description_marker"`
  appeared on every launch) and has been removed.

### Known limitation

- The live test that proved the previous fix wrong has **not** been repeated
  against this build. The root cause is established from that log plus the
  engine bytecode, and the regression tests cover it, but runtime behaviour
  still needs one confirmation in Minecraft.

## [1.1.2] — Movement stall recovery, and the HUD watermark is gone

### Fixed

- **The builder no longer freezes on one block.** A live test showed the player
  standing on a placed block, not moving to the next required position, while the
  schematic was still unfinished. Reading the bundled engine's bytecode gave the
  exact cause: `BuilderProcess.onTick` pauses **only** when it cannot compute a
  goal at all, and has no no-progress counter. If a goal exists but the path to
  it is stale or unreachable, it re-returns that same goal every tick and never
  pauses — so `isPaused()` stayed `false` forever, and `BuildSupervisor`, which
  treated "not paused" as "making progress", had nothing to react to. A movement
  stall produced **no state change anywhere in the build loop**.
- **New `MovementWatchdog` closes that gap.** Every client tick it requires
  *all* of: the build is running, blocks remain, the engine is **not** paused, no
  supply round is in flight, the engine is not already pathing, the player's
  block position is unchanged, and the remaining-block count is unchanged. Only
  after 200 consecutive qualifying ticks (10 s) does it act, so placing a run of
  blocks from one spot, calculating a path, or loading a chunk are all left
  alone. Movement, placement, pathing, a pause, or completion all reset it.
- **Recovery re-plans; it never moves the player.** On a confirmed stall the
  watchdog pauses the builder for one tick and releases it on the next. The
  engine observes the pause, returns `CANCEL_AND_SET_GOAL` and cancels its stale
  path, then recomputes the goal and re-plans. No teleport, no random movement,
  no bypassed collision, no faked completion. Recovery is bounded to 3 attempts
  with a cooldown; when that budget is spent the limitation is reported instead
  of looping.

### Changed

- **`cancelEverything()` / `forceCancel()` are deliberately not used.** Both
  route through the engine's `PathingControlManager`, which calls
  `onLostControl()` on every process; on the builder that nulls its schematic
  field, and `isActive()` is implemented as `schematic != null`. They would have
  silently discarded the build and let the supervisor report it complete. The
  pause/resume pair was chosen because its bytecode shows it only flips one
  boolean. The upstream engine jar is unmodified.
- **The pause and the resume are on different ticks on purpose.** A synchronous
  pause-then-resume would never let the engine *observe* the paused flag, so the
  stale path would never actually be cancelled.
- **Removed the bottom-right "D Web Studio" watermark from the gameplay HUD.**
  The `WatermarkHud` renderer and its registration are deleted outright — not
  hidden or made transparent — so no HUD render path remains and no persistent
  branding is drawn on the gameplay screen. The settings screen credit, the mod
  metadata, and the docs still name D Web Studio, and `[Chika Builder]` chat
  branding and the "Report an issue" link are unchanged. The now-meaningless
  watermark toggle and translation keys are gone; an existing
  `watermarkEnabled` key in a player's config is still read without complaint
  but is no longer written.
- **`#chika_builder debug`** now also reports the movement watchdog's state,
  including the stationary-tick counter that identifies a stall.

### Known limitation

- Whether the re-plan actually frees a given stall is engine behaviour and still
  needs one live confirmation in Minecraft. If the goal is genuinely
  unreachable, the watchdog gives up after its bounded attempts and says so; it
  will not teleport or nudge the player to work around it.

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

## [1.1.2] — live build completion and zero upstream branding

### Fixed

- **The build no longer stalls with material it already has.** The engine asks
  only whether a block is *present* in the player's 36 storage slots - it builds
  its placeable list by scanning them, and in Creative placing does not consume
  the stack. The planner demanded the *full* count instead, so a schematic whose
  totals exceed 36 slots could never be satisfied and the build stopped with
  "N block(s) still missing". Creative is now treated as an effectively
  unlimited source: one of each required block is enough, and a partial count is
  a success. The inventory is still the only proof - a supplier that claims a
  hand-over without the count moving is reported as a failure, never believed.
- **"Already built" is judged the same way the engine judges it.** The engine
  ignores direction/rotation properties (stairs' facing/half/shape, a pillar's
  axis, pipes' side flags, a trapdoor's open) when it decides a cell is done; we
  compared every property exactly, so cells the engine had finished stayed
  outstanding forever. Both sides now use the same rule, and the engine's
  supported `buildIgnoreDirection` option is switched on, so a placement
  orientation can no longer leave a cell unbuildable.
- **Supply never discards the player's items.** The Creative hand-over used to
  empty the inventory menu's non-storage slots, which includes the four armour
  slots; it now only tops up matching stacks and writes into empty slots.
- **The upstream issue link no longer reaches the player.** The engine's
  unhandled-exception line carries its own tracker URL, and it arrived through
  the very chat sink the relay wraps. URLs pointing at the engine's project are
  rewritten to this project's issue page, the engine's name is replaced by
  "Chika Builder" in any casing, and the engine's settings-file name points at
  ours. The engine jar itself is untouched.
- **Chat colour hierarchy.** The prefix is pink; message bodies are white; the
  issue link is white, underlined and clickable, and points at
  `Mr-Divyansh/Chika-Builder-Mod-Minecraft` (taken from this repository's
  `origin` remote).
- **Pause messages say only what is true.** The build reports the exact
  materials still missing (from a live re-plan), whether automatic supply is the
  reason, and never promises a resume that has not happened.

### Changed

- A partial Creative hand-over no longer resumes the engine. The resume
  condition is "blocks arrived **and** a re-plan against the live inventory
  shows every requirement covered" - across all material types. Partial rounds
  keep the engine paused and trigger another round; rounds that deliver nothing
  count against a bounded budget and end in a paused report with the exact
  remainder. Creative still requires the player to be genuinely in Creative, and
  the gamemode is never changed.
- Version stays 1.1.2 (Minecraft 26.1.2 unchanged).

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

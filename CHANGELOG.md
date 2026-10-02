# Changelog

All notable changes to Chika Builder.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions are tagged with the mod version in `gradle.properties`.

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

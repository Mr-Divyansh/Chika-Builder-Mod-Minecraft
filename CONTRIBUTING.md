# Contributing

Thanks for helping with Chika Builder.

## Prerequisites

| | |
|---|---|
| JDK | **25** |
| Gradle | use the wrapper (`.\gradlew.bat`) — no install needed |
| Engine jar | place the third-party build engine at `local/baritone-meteor-26.1.jar` |

The engine jar is **not** in this repository (third-party, LGPL-3.0). Gradle
fails fast with the exact expected path if it is missing. It is covered by
`.gitignore` (`local/`) — never commit it.

## Build and test

```powershell
.\gradlew.bat test     # unit tests only
.\gradlew.bat build    # tests + jar -> build/libs/chika-builder-1.0.0.jar
```

Both must be green before you push. `test` runs JUnit 5 (see
`build.gradle`); failures print in the console.

## Project layout

```text
src/main/java/dev/chika/builder/
├── ChikaBuilderClient.java   entrypoint + wiring + command lockdown
├── Branding.java             product / studio names (single source of truth)
├── command/                  #chika_build, #chika_builder, CommandLockdown
├── build/                    coordinator, outcomes, material/, shop/
├── schematic/                name -> file resolution
├── config/                   persisted settings
├── ui/                       settings screen, watermark
└── platform/baritone/        the only engine-aware package
src/test/java/dev/chika/builder/   mirrors the packages above
```

Read [architecture.md](architecture.md) before touching `build/`.

## Making a change

1. Keep it focused — one concern per change.
2. Follow the rules in [rules.md](rules.md) (they are enforced by tests).
3. Add or extend tests for new behaviour; fix failing ones rather than
   deleting them.
4. Update the docs that describe what you changed — `README.md` for anything
   user-facing, `CHANGELOG.md` for anything releasable.
5. Run `.\gradlew.bat build` and confirm it passes.
6. Commit with a clear message; never include secrets, `build/` output,
   `local/`, logs, or IDE files.

## Style

- Match the surrounding code: 4-space indent, no wildcard imports,
  javadoc on anything non-obvious about *why*.
- Player-facing strings go through `Branding` or
  `assets/chika-builder/lang/en_us.json`, never inline literals that drift.
- No new runtime dependencies without an explicit reason in the PR.

## What will be rejected

- New player-facing commands without updating lockdown + tests + docs.
- Anything that changes the player's game mode or fakes a purchase.
- Engine branding in user-facing text.
- Documented features that do not work.
- Committed secrets or binaries (engine jar, `build/`, screenshots).

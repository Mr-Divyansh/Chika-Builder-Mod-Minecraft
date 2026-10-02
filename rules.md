# Rules

Binding rules for this repository. Each one exists because breaking it has a
concrete cost, and each is (or should be) covered by a test.

## Behaviour

1. **Never fake an outcome.** A purchase — or a Creative hand-over — is only
   reported as successful after the items are confirmed in the inventory. A build
   is only reported as started after the backend accepted it.
2. **Pause instead of guessing.** Missing materials, no shop adapter, a failed
   purchase, an unreadable file → a clear `PAUSED` or `REJECTED` report. Never
   a silent skip, never a crash.
3. **Never change the player's game mode.** Creative is a *permission* the
   player grants plus a *fact* read from the client; both must hold.
4. **Spending is opt-in.** `shop` defaults to `false`, and even enabled it only
   buys the shortfall.
5. **No exploits.** No packet abuse, anti-cheat bypasses, unauthorized server
   mechanisms, or anything the server did not invite.
6. **Already-built blocks are sacred.** Correct blocks are never re-placed,
   re-bought, or counted as missing.
7. **Resume must be free.** Re-running the same command continues; it never
   restarts finished work.

## Surface

8. **Exactly two commands:** `chika_build` and `chika_builder`. Adding a third
   requires updating `CommandLockdown`, its tests, `README.md` and `prd.md` in
   the same change.
9. **Remove, don't hide.** Engine commands are *unregistered* so they can
   neither run nor tab-complete. A help-text filter is not sufficient.
10. **The engine stays invisible.** Its name appears in no player-facing text
    (metadata, chat, branding, backend name). Developer-facing files may name
    it, because a dependency you cannot name is a dependency you cannot audit.
11. **One file, one Mods-screen row.** The engine is merged into this jar; its
    own `fabric.mod.json` must stay excluded.

## Engineering

12. **Pure logic stays pure.** `build/`, `material/`, `shop/`, `schematic/`
    depend on interfaces, not on Minecraft or engine classes, so they can be
    unit-tested without a running game. Engine specifics live in
    `platform/engine` only.
13. **No new dependency without a test that needs it.** The project targets a
    single Minecraft version and a fixed toolchain; version bumps must change
    `gradle.properties`, `fabric.mod.json` (via expansion) and this docs set
    together.
14. **Comments must describe the code as it is.** A comment that contradicts
    the implementation (e.g. "the engine stays a separate jar" while the `jar`
    task merges it) is a bug; the build is a single-file install.
15. **User-facing claims need proof.** If a README line says it works, a test
    or a code path must back it. Features that do not exist are not
    documented — they go in `phases.md` under *Future*.
16. **Tests are part of the change.** New behaviour ships with tests; broken
    tests get fixed, never deleted or `@Disabled`.
17. **Never commit secrets.** `.env`, keys, tokens, credentials and local
    configuration are git-ignored. Credentials never appear in README or code.
18. **Generated output stays out.** `build/`, `.gradle/`, `logs/`, `local/`,
    `run/` are ignored — note `build/` must stay **anchored** (`/build/`), or it
    silently hides `src/.../builder/build/`.

## Documentation

19. **Docs move with the code.** A command, setting or message change updates
    `README.md` in the same commit.
20. **No invented licences, features, or credits.** D Web Studio is credited;
    the licence is the one declared in `fabric.mod.json`.

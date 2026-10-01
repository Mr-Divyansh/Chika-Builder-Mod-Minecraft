# Design

Product, interface and branding rules for Chika Builder.

## Identity

| | |
|---|---|
| Product name | **Chika Builder** |
| Headline form | **CHIKA BUILDER** |
| Byline | **by D Web Studio** |
| Mod id | `chika-builder` |
| Watermark text | `D Web Studio` |

All of these live in one class — `Branding` — so every surface stays
consistent, and `BrandingTest` fails the build if any of them drift.

### The engine is invisible

The internal build/pathing engine is an implementation detail. Its name must
never appear in:

- the mod name, description, or any metadata a player can read;
- `Branding` constants;
- chat messages;
- the backend's user-facing name (`BaritoneBuildService.name()`).

This is enforced by `BrandingTest.noUserFacingBrandingMentionsTheEngine`,
`LayeringTest.engineNameIsNotUserFacingBranding` and
`SingleModEntryTest.userFacingMetadataNeverNamesTheEngine`.

D Web Studio branding stays — it is the studio behind the product.

## Command surface

Exactly two commands, both documented:

```text
#chika_build <filename>.schematic
#chika_builder creative|shop true|false
```

Everything else from the engine is *unregistered* at startup and re-swept for
the first 20 seconds (the engine registers late). Removed means removed: it
cannot be typed and it does not appear in tab completion.

## Voice

Chat messages are plain, specific and never optimistic:

- Prefix with `Chika Builder:` when the message is a standalone problem.
- Say what happened, then what to do: `Build paused - missing materials.`
- Never announce success for something that was not verified.
- Never blame the player; a typo gets the correct usage, not an error dump.

Examples of the intended tone:

```text
Building 'castle.schematic' from (120, 64, -45).
Chika Builder: no schematic named 'castle' in schematics.
Unknown setting 'creativ'. Use: creative or shop.
```

## Interface

- **Settings screen** — titled *CHIKA BUILDER / by D Web Studio*, with
  self-describing toggle buttons (`Creative Building: OFF`), a hint line
  pointing at `#chika_builder`, and a `Done` button.
- **Keybind** — ships **unbound** (`GLFW_KEY_UNKNOWN`), so Chika Builder never
  steals a key the player already uses.
- **Watermark** — a small, faint `D Web Studio` in the corner; **on by default**,
  respects F1 (hidden HUD), never appears in chat, and is itself a toggle.
- **Icon** — `assets/chika-builder/icon.png`, a real square PNG so the Mods
  screen shows the product instead of a `?`. Pinned by `ModIconTest`.
- **Colour** — the UI uses Minecraft's own widgets and palette; no custom
  textures beyond the icon.

## Honesty as a design rule

Three product decisions are visible to the player on purpose:

1. A paused build always lists the actual missing blocks.
2. `shop` never pretends — with no adapter, or a failed purchase, it pauses.
3. `creative` never changes the game mode; a mismatch is explained.

The alternative (silently skipping, silently "buying", silently switching to
Creative) would make the tool unpredictable, so it is treated as a design bug,
not a shortcut.

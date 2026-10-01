# Chika Builder

A focused Minecraft **schematic-building** mod. It exposes exactly one command:

```
#chika_build <filename>.schematic
```

Every other engine command is removed at startup, so they cannot be executed
**or** tab-completed. This includes `#goto`, `#follow`, `#mine`, `#stop`,
`#come`, `#explore`, the old `#build`, `#path`, `#goal`, and the engine's own
`#help` / `#version` / `cancel` / `pause` / `resume` helpers.

Removal is real: commands are **unregistered** from the command registry, so
they are neither runnable nor completable — not merely hidden from help. The
engine's movement / pathing / placement code is untouched, so `#chika_build`
still builds automatically with no separate navigation commands.

---

## Branding — D Web Studio

**Chika Builder** is a product of **D Web Studio**.

| Surface | What it shows |
|---|---|
| Settings / about screen | "Chika Builder" title, with "D Web Studio" beneath it |
| HUD watermark | Small, faint "D Web Studio" in the bottom-right corner |
| Mods screen | The Chika Builder logo instead of the default `?` placeholder |
| Mod metadata | Listed as an author in the mod list |

### Mod icon

The mod icon lives at `src/main/resources/assets/chika-builder/icon.png` and is
registered through the standard Fabric key:

```json
"icon": "assets/chika-builder/icon.png"
```

Fabric renders that resource everywhere a mod's icon appears — the Mods screen,
the mod list entry, and dependency/tooltip panels — so no code is involved and
the icon can never drift from the metadata.

The image is 128×128 PNG, the size Fabric expects for a mod icon. It is
downscaled from the 1254×1254 original with a progressive area-average filter,
which keeps the logo crisp when the Mods screen draws it at 32×32.

Two properties are deliberate and guarded by `ModIconTest`:

- **Square, so it is never stretched.** Fabric draws the icon into a square slot;
  a non-square image would be letterboxed or distorted. The test asserts
  `width == height` and that the edge is at least 128 px.
- **Stored under the mod id.** The path must match the `id` in
  `fabric.mod.json`, or Fabric cannot resolve it and silently falls back to `?`.
  The test asserts both stay in sync.

The source artwork is fully opaque (`Format24bppRgb`, no alpha channel), so
there was no transparency to preserve. The icon is saved as 32-bit ARGB anyway,
so it stays correct if transparent corners are added later.

### Watermark setting

The watermark is **on by default** and can be turned off:

- **In game:** open the settings screen and click the ON/OFF button.
  The screen is opened by binding a key under
  **Options → Controls → Key Binds → "Open Chika Builder Settings"**.
  It ships **unbound**, so it never takes a key you already use.
- **Manually:** edit `.minecraft/config/chika-builder.json`:

```json
{ "watermarkEnabled": true }
```

Set it to `false` to hide the watermark. A missing or corrupt file falls back
to **ON**.

### Deliberate constraints

- **Subtle:** drawn at ~40% alpha in the corner, never centred or large.
- **Never in the way:** hidden when the HUD is hidden (F1) and on any screen
  that isn't gameplay, so it can't cover menus, inventory or your view.
- **No chat spam:** the watermark is never printed in chat.
- **No links or advertising:** no URLs, no "click here", no opening anything.
- **Zero impact on building:** the watermark only reads a boolean and draws
  text. It has no reference to the builder, pathing or placement code, which is
  enforced by `LayeringTest.watermarkLogicIsSeparateFromTheBuilder()`.
- **Negligible cost:** one text draw per frame, no allocation, no disk I/O.
  Settings are read once at startup and written only when you change one.

---

## Third-party dependency

Chika Builder depends on an internal build engine that provides the
movement / pathing / block-placement logic:

| File | Version | Status |
|---|---|---|
| `local/baritone-meteor-26.1.jar` | 26.1 | **Not committed** — third-party, LGPL-3.0 |

This jar is compiled third-party code (Baritone, authored by cabaletta/Brady)
and is **not redistributed in this repository**, because doing so would mean
publishing someone else's binary without its corresponding source, which its
licence expects you to provide.

### Building from a fresh clone

```powershell
# 1. Obtain baritone-meteor-26.1.jar from its original distribution.
# 2. Place it at:
#    local/baritone-meteor-26.1.jar

$env:JAVA_HOME = "C:\path\to\jdk-25"
.\gradlew.bat build
```

The build fails with a clear error if the jar is missing, because
`build.gradle` references it directly:

```groovy
implementation files('local/baritone-meteor-26.1.jar')
```

**Compatibility was verified for Minecraft 26.1.2** — see "Version
compatibility" above.

## Requirements

| Requirement | Version |
|---|---|
| Minecraft | **26.1.2** |
| Java | **25** or newer |
| Fabric Loader | 0.18.6 or newer |
| Fabric API | 0.155.3+26.1.2 |
| baritone-meteor-26.1.jar | required (internal engine) |
| mappings | none — Minecraft 26.1.2 ships unobfuscated |

> **Java 25 is mandatory.** Minecraft 26.1.2 itself requires it. This mod is
> compiled to bytecode major version 69 (Java 25) and will not load on Java 21.

## Version compatibility — how 26.1.2 was verified

Chika Builder previously targeted 26.1. Rather than only editing
`fabric.mod.json`, compatibility was proven by inspecting the real artifacts:

- Both 26.1 and 26.1.2 publish **unobfuscated** client jars (no
  `client_mappings` entry), so no mappings are declared.
- The full Minecraft API surface was diffed between the two versions using ASM:
  **10,682 classes compared, identical class sets (0 added, 0 removed), and
  only 1 class differs** — `net.minecraft.client.gui.components.Checkbox`, which
  merely gained a `ROWS` field and an `overflowsRowLimit(...)` method.
- The internal build engine applies **58 mixins** into Minecraft classes.
  Every one of those mixin targets was compared between 26.1 and 26.1.2 and
  **all are byte-identical**.

**Conclusion: the engine is safe on 26.1.2.** The mod is genuinely compiled
against the 26.1.2 jar (Loom downloaded
`minecraft-merged-deobf-26.1.2.jar`), not merely relabelled.

## Installation

Copy **three** files into `.minecraft/mods/`:

```
.minecraft/mods/
  chika-builder-1.0.0.jar
  baritone-meteor-26.1.jar
  fabric-api-0.155.3+26.1.2.jar
```

`baritone-meteor-26.1.jar` **must be a separate file** — it is deliberately *not*
bundled into `chika-builder.jar`. Baritone ships its own nested jars and a
Fabric mixin plugin, so it cannot be shaded or relocated into another mod.

## Usage

1. Put your schematic in `.minecraft/schematics/`.
2. Stand where you want the schematic's corner.
3. Type `/chika_build house.schematic` in chat.

The extension is optional (`#chika_build house` also works) and matching is
case-insensitive. Tab completion lists whatever is in the schematics folder.

Building, movement, pathing and block verification are handled automatically.

## How the requirements are met

| Requirement | Implementation |
|---|---|
| Only `#chika_build` exposed | `CommandLockdown` unregisters every Baritone command whose name isn't `chika_build` |
| Schematics from `.minecraft/schematics/` | `SchematicLocator` resolves names against `<gamedir>/schematics` |
| Builds automatically | `BaritoneBuildService` delegates to `IBuilderProcess.build(...)` |
| Accurate placement | `buildIgnoreExisting = false` — existing blocks are verified, not blindly trusted |
| Smooth movement / pathing | Baritone's own pathing (A*) and movement control |
| Verify placed blocks | Baritone re-checks blocks around the build each tick (`builderTickScanRadius = 1`) |
| Skip blocks already correct | With `buildIgnoreExisting = false`, Baritone only fixes blocks that do not match |
| Chika Builder branding | `fabric.mod.json` id `chika-builder`, name "Chika Builder"; all chat output |
| Mod icon | `assets/chika-builder/icon.png`, shown in the Mods screen instead of `?` |

## Architecture

The command layer knows **nothing** about Baritone. It talks only to the
`BuildService` interface, so the backend can be swapped later without touching
`#chika_build`:

```
dev.chika.builder
├── Branding                    product/brand constants ("D Web Studio")
├── ChikaBuilderClient          Fabric entrypoint: command, lockdown, branding
├── build/
│   ├── BuildService            <-- the replaceable seam (interface)
│   └── BuildException
├── command/
│   ├── ChikaBuildCommand       #chika_build implementation (no Baritone imports)
│   └── CommandLockdown         removes every other Baritone command
├── config/
│   └── ChikaConfig             watermark ON/OFF, persisted to config/chika-builder.json
├── schematic/
│   └── SchematicLocator        .minecraft/schematics resolution (no Baritone imports)
├── platform/baritone/
│   └── BaritoneBuildService    the ONLY class that imports baritone.*
└── ui/
    ├── ChikaSettingsScreen     settings + about screen (holds the branding)
    └── WatermarkHud            subtle corner watermark
```

`ChikaBuilderClient` references `BaritoneBuildService` directly for convenience
at startup; swapping in a standalone backend is a one-line change there plus a
new class in `platform/`.

Branding is isolated in `Branding`, `config/` and `ui/`. None of those packages
is referenced by `platform/baritone/`, so the watermark can never influence
building or block placement — this is enforced by a unit test.

## Tests

```powershell
.\gradlew.bat test
```

25 tests covering the branding strings (including a guard that they contain no
URLs), the watermark default/toggle/persistence/corrupt-file behaviour, the
mod icon (registered, packaged, decodable, square), and the layering rules above.

## Building from source

```powershell
$env:JAVA_HOME = "C:\tools\jdk-25"
.\gradlew.bat build
```

Output: `build/libs/chika-builder-1.0.0.jar`

## Important build detail: Minecraft 26.1 is unobfuscated

MC 26.1's version manifest publishes only `client` and `server` downloads and
**no `client_mappings` entry** — the game ships unobfuscated, so class names are
already the real ones (`net.minecraft.core.BlockPos`, `net.minecraft.client.Minecraft`).

That is why this project declares **no** mappings, and why Yarn publishes no
26.1 build. `build.gradle` sets:

```properties
fabric.loom.disableObfuscation=true
```

plus `loom { noIntermediateMappings() }` in `build.gradle`. Without both, Loom
fails with either *"Failed to find official mojang mappings for 26.1"* or
*"Configuration 'mappings' has no dependencies"*.

Because nothing needs remapping, `modImplementation` is unavailable and the
plain `implementation` configurations are used instead.

## Not implemented (v1)

`/shop`, auto-buy and any other non-building feature are intentionally absent.
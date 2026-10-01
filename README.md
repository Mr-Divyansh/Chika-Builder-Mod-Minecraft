# Chika Builder

**Chika Builder** is a Minecraft schematic building mod by **D Web Studio**.
It automatically builds `.schematic` files using the Chika Builder build command:

```text
#chika_build castle.schematic
```

Stand where the corner of the build should go, type the command, and Chika
Builder places the blocks for you.

---

## Features

- **Schematic-based automatic building** — reads a `.schematic` (or `.litematic`) file and builds it.
- **Automatic block placement** — you don't place anything by hand.
- **Clear chat feedback** — every build tells you what happened: it started, it was rejected, or it paused.
- **Build pause and resume** — when materials run out, the build pauses, lists exactly what is missing, and continues from where it stopped once you have the items.
- **Creative building mode** — optional and off by default. It never changes your game mode; it only applies when you are actually in Creative.
- **Optional auto-shop purchasing** — off by default, so Chika Builder never spends your money unless you ask it to.
- **Filename tab-completion** — press Tab to see the schematics you have.
- **D Web Studio branding** — settings screen, a faint corner watermark, and the mod icon.
- **A focused command set** — Chika Builder keeps only its own two commands and removes every other command from the built-in engine.

> **About auto-shop:** Chika Builder does not come with a shop of its own, because `/shop`
> works differently on every server. If no shop is connected, the build pauses and lists the
> missing blocks rather than pretending to buy them.

---

## Requirements

| | |
|---|---|
| Minecraft | **26.1.2** |
| Fabric Loader | **0.18.6** or newer |
| Fabric API | build for Minecraft 26.1.2 |
| Java | **25** (the version Minecraft 26.1 runs on) |

---

## Installation

1. **Install Minecraft 26.1.2** and Fabric (Loader **0.18.6**+).
2. **Add Fabric API** for Minecraft 26.1.2 to your `mods` folder.
3. **Put the mod jar inside your mods folder:**

   ```text
   .minecraft/mods/chika-builder-1.0.0.jar
   ```

4. **Start Minecraft.**
5. **Done.** Chika Builder creates and uses:

   ```text
   .minecraft/schematics/
   ```

You install exactly **one** file. The building engine is already merged into
`chika-builder-1.0.0.jar`, so there is nothing else to add to your mods folder.

---

## Schematic Setup

Place your schematic files in:

```text
.minecraft/schematics/
```

The folder is created automatically the first time the mod starts, so you don't
have to make it yourself.

Example:

```text
.minecraft/
└── schematics/
    ├── castle.schematic
    ├── house.schematic
    └── tower.schematic
```

A few convenient details:

- The `.schematic` ending can be left off in the command — `#chika_build castle` works too.
- Names are matched regardless of upper/lower case.
- `.litematic` files work the same way as `.schematic` files.
- Names with `/`, `\` or `..` are rejected, so a command can never reach outside this folder.

---

## Commands

Chika Builder exposes exactly two commands:

| Command | What it does |
|---|---|
| `#chika_build <filename>.schematic` | Builds the named schematic. |
| `#chika_builder creative\|shop true\|false` | Turns a setting on or off. |

Every other command from the built-in engine is removed at startup, so it can
neither be typed nor tab-completed.

### Build

```text
#chika_build <filename>.schematic
```

Builds `<filename>.schematic` from `.minecraft/schematics/`, with the corner of
the build placed at your feet.

```text
#chika_build castle.schematic
#chika_build house
```

**When it works**, you get a short confirmation:

```text
Building 'castle.schematic' from (120, 64, -45).
```

**When it can't get the blocks**, it pauses instead of giving up:

```text
Build paused - missing materials.
Missing:
  Stone x64
Reason:
  Not enough materials in the inventory.
Obtain the items, then run #chika_build again to resume.
```

**If the file isn't there:**

```text
Chika Builder: no schematic named 'castle' in schematics.
```

### Settings

```text
#chika_builder creative true|false
#chika_builder shop true|false
```

| Setting | Default | What it does |
|---|---|---|
| `creative` | `false` | Lets Chika Builder use Creative-mode building. It only applies when you are actually in Creative, and it never changes your game mode. |
| `shop` | `false` | Lets Chika Builder buy missing materials automatically. |

```text
#chika_builder creative true
#chika_builder shop false
```

Both settings are saved to `.minecraft/config/chika-builder.json` and keep their
value between sessions.

If you mistype an argument, the command tells you rather than guessing:

```text
#chika_builder creativ true
```

```text
Unknown setting 'creativ'. Use: creative or shop.
```

---

## How a build gets its blocks

For every block it needs, Chika Builder tries these in order and stops at the
first one that works:

1. **Already built** — the block is already correct in the world, so it is skipped.
2. **Your inventory** — you are carrying enough of the item.
3. **Creative** — only if you turned `creative` on *and* you are actually in Creative.
4. **Shop** — only if you turned `shop` on.
5. **Pause** — nothing could supply it, so the build stops and tells you what is missing.

Step 1 is what makes resuming cheap: blocks finished before a pause are never
requested again, never bought, and never re-placed.

---

## Pause and Resume

When a build pauses you get the exact list of what's missing. Collect those
items, then run the same command again:

```text
#chika_build castle.schematic
```

The build continues from where it stopped. Nothing is lost, and blocks you
already finished are left alone.

---

## Settings Screen

Open the settings screen by binding a key under:

**Options → Controls → Key Binds → "Open Chika Builder Settings"**

It ships **unbound**, so it never takes a key you already use. The screen has
three buttons:

- **Creative Building: ON/OFF**
- **Auto-Shop: ON/OFF**
- **D Web Studio Watermark: ON/OFF**

The watermark is a small, faint "D Web Studio" in the bottom-right corner. It
is **on by default**, disappears when you hide the HUD with F1, and never
appears in chat.

You can also edit the file directly:

```json
{ "watermarkEnabled": false }
```

A missing or unreadable config file falls back to the defaults, so the mod still
starts cleanly.

---

## Troubleshooting

**"no schematic named 'x'"** — the file isn't in `.minecraft/schematics/` or the
name is misspelled. Check the folder and try again.

**"Build paused - missing materials"** — gather the listed items and run the
same command again. Nothing was lost.

**"Creative mode is required"** — you turned `creative` on but you aren't in
Creative. Either switch to Creative or run `#chika_builder creative false`.

**The build starts in the wrong place** — the corner of the build is placed at
your feet, so stand where you want the schematic's starting corner before
typing the command.

---

## Known limitations

These are the honest limits of the current release:

- **Auto-shop ships with no server connected.** There is no universal `/shop`:
  every server implements it differently. Until an adapter for your server is
  registered, `#chika_builder shop true` makes builds **pause** with a clear
  "no shop is available" message. A purchase is never faked.
- **Only `.schematic` and `.litematic` files** are recognised.
- **Creative building never changes your game mode.** It only applies when you
  are already in Creative; otherwise the build pauses and says so.
- **Schematics are read from `.minecraft/schematics/` only** — nothing outside
  that folder can be reached by a command.
- **Minecraft 26.1.2 exactly.** The mod declares that single version; no other
  Minecraft version is claimed to work.
- Build decisions are covered by the automated test suite; the mod is not
  exercised by an automated in-game run.

---

## Building from source

```powershell
.\gradlew.bat build
```

Output: `build/libs/chika-builder-1.0.0.jar`

Requires JDK 25, plus the build-time engine jar placed in `local/`. That jar is
deliberately not committed; the exact filename is documented in `.gitignore`, and
Gradle prints the expected path if it is missing.

---

## Dependencies

| Dependency | How you get it |
|---|---|
| Fabric Loader 0.18.6+ | installed with Fabric |
| Fabric API for 26.1.2 | your `mods/` folder |
| Pathing & build engine | **already merged inside `chika-builder-1.0.0.jar`** |

You never install the engine separately. It is third-party (LGPL-3.0) code that
is merged into the Chika Builder jar when it is built, so the Mods screen lists
exactly one entry — *Chika Builder* — and your `mods/` folder needs exactly one
file.

---

## Documentation

| File | What it covers |
|---|---|
| [architecture.md](architecture.md) | modules, layering, and how they depend on each other |
| [ai-loop.md](ai-loop.md) | analyze → plan → supply → build → verify → recover |
| [memory.md](memory.md) | persisted settings and runtime state |
| [design.md](design.md) | product, UI and branding rules |
| [phases.md](phases.md) | completed, current and future work |
| [prd.md](prd.md) | product requirements and feature specification |
| [rules.md](rules.md) | engineering rules the code must follow |
| [CONTRIBUTING.md](CONTRIBUTING.md) | how to build, test and contribute |
| [CHANGELOG.md](CHANGELOG.md) | release history |

---

## Credits

**Chika Builder** is a product of **D Web Studio**.

Licensed under LGPL-3.0.



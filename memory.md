# Memory

What Chika Builder remembers, where it remembers it, and — just as important —
what it deliberately does **not** remember.

## Persisted state

One file: **`.minecraft/config/chika-builder.json`**

```json
{
  "creativeEnabled": false,
  "shopEnabled": false
}
```

| Key | Setting command | Default | Meaning |
|---|---|---|---|
| `watermarkEnabled` | *(removed)* | — | retired: the HUD watermark was deleted. Still read from an existing file so nothing breaks, but never written and it controls nothing. |
| `creativeEnabled` | `#chika_builder creative true\|false` | `false` | Creative supply rung is *allowed* |
| `shopEnabled` | `#chika_builder shop true\|false` | `false` | automatic purchasing is *allowed* |

Rules (`ChikaConfig`):

- **Written only when a value actually changes.** Nothing is written per tick
  or per build, so the file costs nothing at runtime.
- **A missing file is created with defaults** on first load.
- **A corrupt or unreadable file falls back to defaults** — creative off,
  shop off — and the game still starts.
- **Forward compatible:** keys written by older versions are simply absent and
  keep their default, so downgrading and upgrading both load cleanly.
- **Backwards compatible:** unknown extra keys are ignored.

The two boolean settings are the *permission*, not the *state*: turning
`creative` on never changes your gamemode (it only lets blocks be handed over
*while* you are genuinely in Creative, and each hand-over is confirmed in your
inventory before it counts), and turning `shop` on never spends money by itself.

## Runtime state (in-memory only)

| Where | What | Lifetime |
|---|---|---|
| `ChikaBuilderClient` | locator, build service, coordinator, lockdown tick counter | game session |
| `ShopRegistry` | registered `ShopAdapter` instances | until unregistered / disconnect / test cleanup |
| `ChikaConfig` | current values | session (re-read from disk on start) |
| Command lockdown | which engine commands are still registered | re-asserted on a timer |

## Not persisted (deliberately)

- **Build progress.** Chika Builder has no build-state file. Progress is
  *derivable* instead: the next run re-reads the schematic and asks the world
  which blocks are already correct. That is why resuming after a pause — or
  after restarting the game — needs no bookkeeping and can never drift out of
  sync with reality.
- **Purchases.** Nothing is remembered about what was bought; the inventory is
  re-read live every time.
- **Creative hand-overs.** Nothing is remembered about what Creative supplied
  either; like purchases, the inventory is re-read live, so a hand-over is never
  trusted from a record — only from the current count.
- **Schematic contents.** Files are read on demand; nothing is cached to disk.

## Where the inputs live

```text
.minecraft/
├── config/
│   └── chika-builder.json     ← settings above
└── schematics/
    ├── castle.schematic       ← build inputs, created automatically if absent
    └── house.schematic
```

No account data, tokens, server credentials or personal information is ever
read or written by Chika Builder.

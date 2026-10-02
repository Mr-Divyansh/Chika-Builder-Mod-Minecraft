# The build loop

What happens between typing `#chika_build castle.schematic` and the last block
being placed. Every step below is a real class in the repository — nothing here
is aspirational.

## 1. Resolve

`SchematicLocator.find()` maps what you typed onto
`.minecraft/schematics/`:

- `.schematic` and `.litematic` are accepted;
- the extension may be omitted and matching ignores case;
- names containing `/`, `\` or `..` are rejected outright, so a command can
  never read outside the folder;
- no match → **`REJECTED`** with `no schematic named 'x' in schematics.`

Tab completion lists the files that actually exist.

## 2. Analyze

`SchematicAnalyzer.analyze(file, origin)` (implemented by
`ChikaSchematicAnalyzer`) loads the schematic and returns one
`MaterialNeed` per distinct block type, anchored at the origin — your feet.

An unreadable or malformed file raises `SchematicAnalysisException`, which the
coordinator turns into **`REJECTED`**: `Could not read 'castle.schematic': …`.
A bad file is reported, never crashed on.

## 3. Compare against the world

`ExistingBlockChecker` asks which required blocks are *already correct* at
their target position. Those become `MaterialResolution.ALREADY_PLACED`:

- they are not counted as missing;
- they are never bought;
- they are never re-placed.

This is what makes resuming cheap — a half-built structure does not re-request
the half that is done.

## 4. Plan the supply

`MaterialPlanner.plan(...)` is pure logic and applies one ladder to every
block, in this fixed order:

| # | Resolution | Condition |
|---|---|---|
| 1 | `ALREADY_PLACED` | the world already satisfies it |
| 2 | `INVENTORY` | you are holding enough of the item |
| 3 | `CREATIVE` | `creative` is on **and** you are really in Creative |
| 4 | `SHOP` | `shop` is on |
| 5 | `MISSING` | nothing could supply it → the build **pauses** |

Two safety properties, both test-covered:

- Creative is **never assumed**. The rung reads the client's actual gamemode;
  with `creative=true` but a non-Creative player, the rung is skipped and the
  pause message explains the mismatch.
- Nothing is routed to the shop that an earlier rung already covers, so the
  shop is never asked for blocks you already own.

`CREATIVE` and `SHOP` are **plans, not holdings**: a block routed to either one
is *not* satisfied, and the build cannot start until the blocks have really been
handed over (step 5) or bought (step 6).

## 5. Hand over from Creative (only if the plan needs it)

`CreativeAcquisition.acquire(plan)` takes every block the plan routed to
Creative and proves each one arrived:

1. re-checks that Creative is genuinely available (the setting is a permission,
   the gamemode is the fact);
2. asks the injected `CreativeSupplier` for the **shortfall** only;
3. re-counts the inventory afterwards and treats only a real increase as a
   hand-over.

`CreativeInventorySupplier` is the Minecraft-side implementation: it uses the
game's own Creative hand-over (`handleCreativeModeItemAdd`) and mirrors each slot
write into the open menu, exactly as the Creative inventory does. It never sends a
packet by hand and never changes the gamemode.

A hand-over that delivers nothing, or only part of the shortfall (a full
inventory, say), is reported with the exact numbers and the build **pauses** on
the remainder. Nothing is ever counted as supplied on hope.

## 6. Supply what is missing (only if `shop` is on)

`PurchaseOrchestrator.fulfil(plan)`:

1. re-checks the live inventory (the plan may be stale);
2. asks `ShopRegistry.active()` for an available `ShopAdapter`;
3. buys **only the shortfall**, never the total requirement;
4. **verifies** — re-reads the inventory and confirms the expected count
   actually arrived.

Any of these fails → the purchase is reported as failed and the build pauses:
insufficient money, item not in the shop, shop unavailable, no adapter
registered, or items that never arrived. A failure is described exactly;
a success is only claimed when the items are confirmed in inventory.

Chika Builder ships **no adapter**, because `/shop` is server-specific. With
none registered, `shop=true` results in an honest pause.

## 7. Build

If everything is covered, `BuildCoordinator` calls
`BuildService.startBuild(schematic, origin)` →
`ChikaBuildService`, which anchors the schematic at the origin, tunes the
engine's builder settings for accurate (non-ignored) placement and layered
building, and starts the builder process.

Outcome: **`STARTED`** with the file and origin, e.g.

```text
Building 'castle.schematic' from (120, 64, -45).
```

## 8. Verify and place

The engine places each block and re-checks the world as it works — Chika
Builder never reports success for a placement it did not confirm. Chika's own
verification lives at the *supply* boundary (steps 5 and 6): no Creative
hand-over and no purchase is ever assumed to have worked.

## 9. Recover

Nothing is lost when a build stops:

- **Paused** → gather the listed items (or enable a supply method) and run the
  *same* command again. The loop restarts at step 2, and step 3 keeps everything
  already built out of the new requirement.
- **Rejected** → fix the file/name and run it again.
- **Running** → the engine keeps building until the schematic is complete.

```text
┌──────────┐  REJECTED   ┌───────────┐
│  resolve │────────────▶│  report   │
└────┬─────┘             └───────────┘
     ▼
┌──────────┐   MISSING   ┌───────────┐
│ analyze  │────────────▶│  PAUSED   │◀── purchase failed
└────┬─────┘             └─────┬─────┘
     ▼                         │ player supplies items,
┌──────────┐                   │ runs the same command
│  compare │                   │ (back to step 2)
└────┬─────┘                   │
     ▼                         │
┌──────────┐  still short      │
│  plan    │───────────────────┘
└────┬─────┘
     ▼
┌──────────┐  creative on  ┌──────────────┐
│  supply  │──────────────▶│ Creative     │── verified ──┐
└────┬─────┘               │ hand-over    │              │
     │                     └──────────────┘              │
     │  shop on  ┌──────────┐                            │
     ├──────────▶│ purchase │── verified ──┐             │
     │           └──────────┘              │             │
     │ covered                             ▼             ▼
     ▼           ┌────────────────────────────────────────────┐
     └──────────▶│ build → verify → continue → complete → done│
                 └────────────────────────────────────────────┘
```

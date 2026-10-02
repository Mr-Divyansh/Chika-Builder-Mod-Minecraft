# Architecture

How Chika Builder is put together, from the chat command down to the block
being placed.

## The chain

```text
   player
     │   #chika_build castle.schematic
     ▼
 command/                thin: resolve the name, run the flow, print the report
   ├─ ChikaBuildCommand
   ├─ ChikaBuilderCommand
   └─ CommandLockdown     keeps ONLY those two commands, removes every other
     │
     ▼
 build/                  decision logic — no Minecraft or engine classes
   ├─ BuildCoordinator    runs the fixed sequence, decides STARTED / PAUSED / REJECTED
   ├─ material/           analyzer + inventory overlay + priority ladder + Creative hand-over
   └─ shop/               purchase orchestration + adapter registry
     │
     ▼
 interfaces               BuildService, SchematicAnalyzer, PlayerContext, CreativeSupplier
     │
     ▼
 platform/engine/         the one package that knows the engine
   ├─ ChikaBuildService
   ├─ ChikaSchematicAnalyzer
   ├─ ExistingBlockChecker
   ├─ MinecraftPlayerContext
   ├─ CreativeInventorySupplier
   ├─ Inventories
   └─ ItemIds
```

## Packages

| Package | Responsibility | Talks to Minecraft / engine? |
|---|---|---|
| `dev.chika.builder` | client entrypoint, wiring, branding constants | yes (bootstrap only) |
| `…builder.command` | the two Chika commands + command lockdown | extends the engine's command base class only to be registered |
| `…builder.build` | build lifecycle and outcome reporting | no |
| `…builder.build.material` | what the schematic needs and how each block is supplied | no (interfaces only) |
| `…builder.build.shop` | buying the shortfall, honestly | no (interfaces only) |
| `…builder.schematic` | resolving typed names against `.minecraft/schematics/` | no |
| `…builder.config` | persisted settings | Fabric Loader only (path lookup) |
| `…builder.ui` | settings screen, watermark | yes |
| `…builder.platform.engine` | the actual build/pathing implementation | yes — **this is the only implementation package** |

The rule the tests enforce: `build/`, `material/`, `shop/` and `schematic/` are
pure logic, so the whole pause/resume decision is unit-testable without
launching a game. Anything engine-specific lives behind the interfaces above
and is implemented in `platform/engine`.

## Startup sequence

`ChikaBuilderClient.onInitializeClient()`:

1. Resolve `.minecraft/schematics/` and create it (`mkdirs()`), logging a
   warning if that fails.
2. `ChikaConfig.load()` — read `config/chika-builder.json`.
3. Wire `ChikaBuildService`, `ChikaSchematicAnalyzer`, `MinecraftPlayerContext`,
   `PurchaseOrchestrator`, `CreativeInventorySupplier` and `BuildCoordinator`.
4. On the first client tick — with a retry loop of up to 20 attempts — register
   `#chika_build` and `#chika_builder`, then run `CommandLockdown.enforce()`.
5. Keep re-running the lockdown for the first 400 ticks, because the engine
   registers its own commands *after* mod initialisation. Anything that is not
   ours is unregistered, not merely hidden.
6. Register the D Web Studio watermark, the (unbound by default) settings
   keybind, and the settings screen.

## Engine integration

The pathing/building engine is a **third-party, LGPL-3.0** dependency that is
compiled against and then **merged into this mod's jar** by the `jar` task:

- consumed from `local/baritone-meteor-26.1.jar`, which is **never committed**
  (`.gitignore` → `local/`); Gradle fails with the expected path if it is missing;
- its `fabric.mod.json` is **excluded**, so the Mods screen shows one row —
  *Chika Builder* — never a second one;
- its mixin config file is renamed to `mixins.chika_engine.json`, which is safe
  because the plugin gates on the mixin **class** names, not the file name;
- its nested library (`META-INF/jars/nether-pathfinder-1.4.1.jar`) is kept and
  declared by *our* `fabric.mod.json`.

None of the engine's commands survive: `CommandLockdown` unregisters everything
except `chika_build` and `chika_builder`. Only the movement/pathing/block
placement code is used, and only through `BuildService`.

`SingleModEntryTest` and `LayeringTest` pin all of these properties.

## Resources

| File | Purpose |
|---|---|
| `resources/fabric.mod.json` | id, name, icon, entrypoint, mixin + nested-jar declarations |
| `resources/assets/chika-builder/icon.png` | Mods-screen icon (square PNG) |
| `resources/assets/chika-builder/lang/en_us.json` | command help text shown by the engine's `?` |

## Build system

| | |
|---|---|
| Gradle | 9.8.0 (wrapper) |
| Loom | 1.18.2, `noIntermediateMappings()` |
| Mappings | none — Minecraft 26.1 ships unobfuscated, so there is nothing to remap |
| Java | toolchain 25, `options.release = 25` |
| Output | `build/libs/chika-builder-1.1.2.jar` |

## Tests

| Group | Guards |
|---|---|
| `BuildCoordinatorTest` | the full flow: skip-correct-blocks, pause, resume, creative hand-over and mismatch |
| `CreativeAcquisitionTest` | the Creative rung: shortfall only, and a hand-over is only counted once the inventory confirms it |
| `MaterialPlannerTest` | the priority ladder and shortfall maths |
| `ChikaBuilderCommandTest` | `#chika_builder` parsing and Tab completion (setting names and `true`/`false`) |
| `PurchaseOrchestratorTest` | purchases never faked; failures pause |
| `ShopRegistryTest` | ships empty; adapter registration semantics |
| `CommandLockdownTest` | exactly two commands allowed, forbidden list enforced |
| `ChikaConfigTest` | defaults, persistence, corruption, forward compatibility |
| `SingleModEntryTest` / `ModIconTest` | single Mods-screen entry, icon, metadata |
| `BrandingTest` / `LayeringTest` | branding strings, package separation |

See [rules.md](rules.md) for the rules these tests encode.

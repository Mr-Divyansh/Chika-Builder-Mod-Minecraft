package dev.chika.builder.build;

import dev.chika.builder.build.material.CreativeSupplier;
import dev.chika.builder.build.material.ItemAmount;
import dev.chika.builder.build.material.MaterialNeed;
import dev.chika.builder.build.material.MaterialResolution;
import dev.chika.builder.build.material.PlayerContext;
import dev.chika.builder.build.material.SchematicAnalyzer;
import dev.chika.builder.build.shop.ItemRequest;
import dev.chika.builder.build.shop.PurchaseOrchestrator;
import dev.chika.builder.build.shop.PurchaseReport;
import dev.chika.builder.build.shop.PurchaseResult;
import dev.chika.builder.build.shop.ShopAdapter;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the {@code #chika_build} flow: required blocks are
 * calculated, existing blocks are skipped, the inventory is consulted, the shop
 * only runs when enabled, and an unobtainable build pauses and resumes cleanly.
 */
class BuildCoordinatorTest {

    private static final File SCHEMATIC = new File("castle.schematic");
    private static final BuildService.Origin ORIGIN = new BuildService.Origin(10, 64, -20);

    /** Counts how many builds were actually started. */
    private static final class RecordingBuildService implements BuildService {

        private int started;
        private File lastSchematic;

        @Override
        public String name() {
            return "test";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void startBuild(File schematic, Origin origin) {
            this.started++;
            this.lastSchematic = schematic;
        }

        @Override
        public boolean isBuilding() {
            return this.started > 0;
        }

        @Override
        public boolean cancel() {
            return false;
        }
    }

    private static final class StubAnalyzer implements SchematicAnalyzer {

        /** Mutable so a test can simulate blocks being placed between attempts. */
        private final List<MaterialNeed> needs;

        StubAnalyzer(List<MaterialNeed> needs) {
            this.needs = new ArrayList<>(needs);
        }

        @Override
        public List<MaterialNeed> analyze(File schematic, BuildService.Origin origin) {
            return this.needs;
        }
    }

    /** Mutable player state so a test can simulate inventory and gamemode. */
    private static final class StubPlayer implements PlayerContext {

        private final Map<String, Integer> counts = new HashMap<>();
        private boolean creative;

        StubPlayer set(String id, int count) {
            this.counts.put(id, count);
            return this;
        }

        StubPlayer creative(boolean value) {
            this.creative = value;
            return this;
        }

        @Override
        public boolean isActuallyInCreative() {
            return this.creative;
        }

        @Override
        public int countItem(String itemId) {
            return this.counts.getOrDefault(itemId, 0);
        }
    }

    private static final class StubSettings implements BuildCoordinator.Settings {

        private final boolean creative;
        private final boolean shop;

        StubSettings(boolean creative, boolean shop) {
            this.creative = creative;
            this.shop = shop;
        }

        @Override
        public boolean isCreativeEnabled() {
            return this.creative;
        }

        @Override
        public boolean isShopEnabled() {
            return this.shop;
        }
    }

    /** Hands over items when asked to, and records every request. */
    private static final class DeliveringAdapter implements ShopAdapter {

        private final StubPlayer player;
        private final PurchaseResult result;
        private final List<ItemRequest> requests = new ArrayList<>();

        DeliveringAdapter(StubPlayer player, PurchaseResult result) {
            this.player = player;
            this.result = result;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public PurchaseResult purchase(ItemRequest request) {
            this.requests.add(request);
            if (this.result.success()) {
                this.player.set(request.itemId(), request.amount());
            }
            return this.result;
        }

        @Override
        public String describe() {
            return "stub";
        }
    }

    /** Hands blocks into the player's inventory, the way Creative really would. */
    private static final class StubCreative implements CreativeSupplier {

        private final StubPlayer player;
        private final boolean available;
        private final int capPerGrant;
        private final List<String> requests = new ArrayList<>();

        StubCreative(StubPlayer player, boolean available) {
            this(player, available, Integer.MAX_VALUE);
        }

        /**
         * @param capPerGrant how much a single hand-over can deliver, so a test
         *                    can simulate a full inventory
         */
        StubCreative(StubPlayer player, boolean available, int capPerGrant) {
            this.player = player;
            this.available = available;
            this.capPerGrant = capPerGrant;
        }

        @Override
        public boolean isAvailable() {
            return this.available;
        }

        @Override
        public int grant(String itemId, int amount) {
            this.requests.add(itemId + " x" + amount);
            int delivered = Math.min(amount, this.capPerGrant);

            if (delivered > 0) {
                this.player.set(itemId, this.player.countItem(itemId) + delivered);
            }

            return delivered;
        }

        @Override
        public String describe() {
            return "stub";
        }
    }

    private static MaterialNeed need(String id, String label, int required,
                                    int placed, int held) {
        return new MaterialNeed(new ItemAmount(id, label, required), required, placed, held,
                MaterialResolution.MISSING);
    }

    private static BuildCoordinator coordinator(BuildService buildService, StubAnalyzer analyzer,
                                                StubPlayer player, BuildCoordinator.Settings settings,
                                                ShopAdapter adapter) {
        return coordinator(buildService, analyzer, player, settings, adapter,
                CreativeSupplier.NONE);
    }

    private static BuildCoordinator coordinator(BuildService buildService, StubAnalyzer analyzer,
                                                StubPlayer player, BuildCoordinator.Settings settings,
                                                ShopAdapter adapter, CreativeSupplier creative) {
        PurchaseOrchestrator purchases = new PurchaseOrchestrator(
                () -> Optional.ofNullable(adapter), player::countItem);
        return new BuildCoordinator(buildService, analyzer, player, purchases, creative, settings);
    }

    @Test
    void buildsWhenTheInventoryCoversEverything() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer().set("minecraft:stone", 248);

        BuildOutcome outcome = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 248))),
                player, new StubSettings(false, false), null)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(outcome.isStarted());
        assertEquals(1, service.started);
    }

    @Test
    void correctBlocksInTheWorldAreSkippedAndNeverBought() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer();
        DeliveringAdapter shop = new DeliveringAdapter(player, PurchaseResult.bought(48));

        // 248 required, 200 already built, 0 held -> only 48 outstanding.
        BuildOutcome outcome = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 200, 0))),
                player, new StubSettings(false, true), shop)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(outcome.isStarted());
        assertEquals(1, shop.requests.size());
        assertEquals(48, shop.requests.get(0).amount(),
                "blocks already placed must not be requested from the shop");
    }

    @Test
    void shopIsNeverContactedWhenDisabled() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer();
        DeliveringAdapter shop = new DeliveringAdapter(player, PurchaseResult.bought(248));

        BuildOutcome outcome = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                player, new StubSettings(false, false), shop)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(shop.requests.isEmpty(), "shop must not run when shop=false");
        assertTrue(outcome.isPaused());
        assertEquals(0, service.started);
    }

    @Test
    void missingMaterialsPauseAndReportExactlyWhatIsMissing() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer();

        BuildOutcome outcome = coordinator(service, new StubAnalyzer(List.of(
                need("minecraft:stone", "Stone", 248, 0, 0),
                need("minecraft:oak_planks", "Oak Planks", 96, 0, 0))),
                player, new StubSettings(false, false), null)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(outcome.isPaused());
        assertEquals(0, service.started, "an unobtainable build must not start");

        String report = String.join("\n", outcome.lines());
        assertTrue(report.contains("Build paused"), report);
        assertTrue(report.contains("Stone x248"), report);
        assertTrue(report.contains("Oak Planks x96"), report);
        assertTrue(report.contains("Missing:"), report);
    }

    @Test
    void creativeWorksOnlyWhenThePlayerIsActuallyInCreative() {
        RecordingBuildService service = new RecordingBuildService();

        // creative=true but the player is in survival -> must pause.
        BuildOutcome paused = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                new StubPlayer(), new StubSettings(true, false), null)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(paused.isPaused(), "creative must not be granted outside Creative");
        assertEquals(0, service.started);

        // creative=true and genuinely in Creative, with a working Creative
        // source -> the blocks are really handed over, then the build starts.
        RecordingBuildService service2 = new RecordingBuildService();
        StubPlayer creativePlayer = new StubPlayer().creative(true);
        BuildOutcome started = coordinator(service2,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                creativePlayer, new StubSettings(true, false), null,
                new StubCreative(creativePlayer, true))
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(started.isStarted());
        assertEquals(1, service2.started);
        assertEquals(248, creativePlayer.countItem("minecraft:stone"),
                "the blocks must actually be in the inventory before the build starts");
    }

    @Test
    void creativeTakesOnlyTheShortfallAndNeverReplacesExistingBlocks() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer().creative(true);
        StubCreative creative = new StubCreative(player, true);

        // 248 required, 200 already built, 0 held -> Creative owes 48, not 248.
        BuildOutcome outcome = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 200, 0))),
                player, new StubSettings(true, false), null, creative)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(outcome.isStarted());
        assertEquals(List.of("minecraft:stone x48"), creative.requests,
                "blocks already placed must never be re-supplied");
    }

    @Test
    void aCreativeHandoverThatDeliversNothingPausesTheBuild() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer().creative(true);

        // Available, but the hand-over delivers nothing: the build must not start.
        StubCreative empty = new StubCreative(player, true, 0);

        BuildOutcome outcome = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                player, new StubSettings(true, false), null, empty)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(outcome.isPaused());
        assertEquals(0, service.started, "nothing may be built from blocks we never got");
        assertTrue(String.join("\n", outcome.lines()).contains("Creative did not supply"),
                String.join("\n", outcome.lines()));
    }

    @Test
    void anUnavailableCreativeSourcePausesAndNeverChangesTheGameMode() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer().creative(true);

        // The supplier reports itself unavailable - for example the player left
        // Creative between planning and the hand-over.
        StubCreative unavailable = new StubCreative(player, false);

        BuildOutcome outcome = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                player, new StubSettings(true, false), null, unavailable)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(outcome.isPaused());
        assertTrue(unavailable.requests.isEmpty(),
                "an unavailable Creative source must not be asked for blocks");
        assertTrue(player.isActuallyInCreative(),
                "the player's gamemode is only ever read, never written");
        assertTrue(String.join("\n", outcome.lines()).contains("Creative mode is not available"),
                String.join("\n", outcome.lines()));
    }

    @Test
    void aPartialHandoverPausesAndReportsTheShortfall() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer().creative(true);

        // Only 64 of the 248 blocks fit, so the build must not start yet.
        StubCreative limited = new StubCreative(player, true, 64);

        BuildOutcome outcome = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                player, new StubSettings(true, false), null, limited)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(outcome.isPaused());
        assertEquals(0, service.started);
        assertEquals(64, player.countItem("minecraft:stone"),
                "the blocks that did arrive stay in the inventory");

        String report = String.join("\n", outcome.lines());
        assertTrue(report.contains("Only 64 of 248"), report);
        assertTrue(report.contains("Stone x248"), report);
    }

    @Test
    void creativeIsNeverUsedWhenTheSettingIsOff() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer().creative(true);
        StubCreative creative = new StubCreative(player, true);

        BuildOutcome outcome = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                player, new StubSettings(false, false), null, creative)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(outcome.isPaused());
        assertTrue(creative.requests.isEmpty(),
                "being in Creative must not be used unless the setting is on");
    }

    @Test
    void creativeMismatchIsExplainedToThePlayer() {
        RecordingBuildService service = new RecordingBuildService();

        BuildOutcome outcome = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                new StubPlayer(), new StubSettings(true, false), null)
                .requestBuild(SCHEMATIC, ORIGIN);

        String report = String.join("\n", outcome.lines());
        assertTrue(report.contains("Creative mode is required"), report);
        assertTrue(report.contains("you are not in Creative"), report);
    }

    @Test
    void insufficientMoneyPausesAndKeepsProgress() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer();
        DeliveringAdapter shop = new DeliveringAdapter(player,
                PurchaseResult.failed("Insufficient money"));

        BuildOutcome outcome = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                player, new StubSettings(false, true), shop)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(outcome.isPaused());
        assertEquals(0, service.started);
        assertTrue(String.join("\n", outcome.lines()).contains("Insufficient money"));
    }

    @Test
    void unavailableShopItemPausesTheBuild() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer();
        DeliveringAdapter shop = new DeliveringAdapter(player,
                PurchaseResult.failed("Not sold on this server"));

        BuildOutcome outcome = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                player, new StubSettings(false, true), shop)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(outcome.isPaused());
        assertTrue(String.join("\n", outcome.lines()).contains("Not sold on this server"));
    }

    @Test
    void aPausedBuildResumesOnceTheItemsArrive() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer();
        StubAnalyzer analyzer = new StubAnalyzer(
                List.of(need("minecraft:stone", "Stone", 248, 0, 0)));
        BuildCoordinator underTest = coordinator(service, analyzer, player,
                new StubSettings(false, false), null);

        // First attempt: nothing available -> paused.
        assertTrue(underTest.requestBuild(SCHEMATIC, ORIGIN).isPaused());
        assertEquals(0, service.started);

        // The player obtains the blocks; the schematic is unchanged.
        player.set("minecraft:stone", 248);
        analyzer.needs.set(0, need("minecraft:stone", "Stone", 248, 0, 248));

        // Second attempt: resumes from where it stopped, no restart needed.
        BuildOutcome resumed = underTest.requestBuild(SCHEMATIC, ORIGIN);
        assertTrue(resumed.isStarted());
        assertEquals(1, service.started);
        assertEquals(SCHEMATIC, service.lastSchematic);
    }

    @Test
    void resumeDoesNotRebuildBlocksThatAreAlreadyCorrect() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer().set("minecraft:stone", 48);

        // After a pause the player placed 200 of the 248 themselves.
        StubAnalyzer analyzer = new StubAnalyzer(
                List.of(need("minecraft:stone", "Stone", 248, 200, 48)));

        BuildOutcome outcome = coordinator(service, analyzer, player,
                new StubSettings(false, false), null)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(outcome.isStarted(),
                "a partially built schematic must resume, not restart");
        assertEquals(48, outcome.plan().needs().get(0).outstanding(),
                "only the 48 outstanding blocks remain");
    }

    @Test
    void anUnreadableSchematicIsRejectedRatherThanCrashing() {
        RecordingBuildService service = new RecordingBuildService();
        SchematicAnalyzer broken = (file, origin) -> {
            throw new SchematicAnalyzer.SchematicAnalysisException("corrupt file");
        };

        BuildCoordinator underTest = new BuildCoordinator(service, broken,
                new StubPlayer(),
                new PurchaseOrchestrator(() -> Optional.empty(), id -> 0),
                CreativeSupplier.NONE,
                new StubSettings(false, false));

        BuildOutcome outcome = underTest.requestBuild(SCHEMATIC, ORIGIN);

        assertEquals(BuildStatus.REJECTED, outcome.status());
        assertEquals(0, service.started);
    }
}


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
        private int resumed;
        private boolean paused;
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
        public boolean isPaused() {
            return this.paused;
        }

        @Override
        public void resume() {
            this.resumed++;
            this.paused = false;
        }

        @Override
        public boolean cancel() {
            return false;
        }
    }

    private static final class StubAnalyzer implements SchematicAnalyzer {

        /** Mutable, so a test can simulate blocks being placed between attempts. */
        private final List<MaterialNeed> needs;

        /** Counts how many times the schematic was actually re-analysed. */
        private int analyses;

        StubAnalyzer(List<MaterialNeed> needs) {
            this.needs = new ArrayList<>(needs);
        }

        @Override
        public List<MaterialNeed> analyze(File schematic, BuildService.Origin origin) {
            this.analyses++;
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
        private int capPerGrant;
        private final List<String> requests = new ArrayList<>();
        private final List<String> blocked = new ArrayList<>();

        StubCreative(StubPlayer player, boolean available) {
            this(player, available, Integer.MAX_VALUE);
        }

        /**
         * @param capPerGrant how much of this item the inventory can hold in
         *                    total, so a test can simulate a full inventory
         */
        StubCreative(StubPlayer player, boolean available, int capPerGrant) {
            this.player = player;
            this.available = available;
            this.capPerGrant = capPerGrant;
        }

        /**
         * Raises the per-item capacity, as if the player freed room for more
         * stacks between supply rounds.
         */
        StubCreative capacity(int capPerItem) {
            this.capPerGrant = capPerItem;
            return this;
        }

        /** Simulates an item the inventory has no room for at all. */
        StubCreative rejects(String itemId) {
            this.blocked.add(itemId);
            return this;
        }

        /** Makes a previously blocked item obtainable again. */
        StubCreative accepts(String itemId) {
            this.blocked.remove(itemId);
            return this;
        }

        @Override
        public boolean isAvailable() {
            return this.available;
        }

        @Override
        public int grant(String itemId, int amount) {
            this.requests.add(itemId + " x" + amount);

            // Blocked means the inventory has no room for this item at all.
            if (this.blocked.contains(itemId)) {
                return 0;
            }

            // The cap is inventory capacity, not a per-call limit: once the item
            // is at capacity nothing more fits, however many times the
            // acquisition loop asks. That is what makes a genuinely full
            // inventory stop the hand-over instead of granting forever.
            int room = Math.max(0, this.capPerGrant - this.player.countItem(itemId));
            int delivered = Math.min(amount, room);

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

    /**
     * A partial <i>count</i> is enough; only a block that never arrives is fatal.
     *
     * <p>The engine needs at least one of each required block in the player's
     * 36 storage slots and never consumes the stack in Creative, so a schematic
     * must not be declared unsatisfiable just because 36 slots cannot hold every
     * total at once - which is what stalled the live test at "57 block(s) still
     * missing".
     */
    @Test
    void aPartialCountIsEnoughAndTheBuildStarts() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer().creative(true);

        // Only one stack of the 248 fits; presence is all the engine needs.
        StubCreative limited = new StubCreative(player, true, 64);

        BuildOutcome outcome = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                player, new StubSettings(true, false), null, limited)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(outcome.isStarted());
        assertEquals(1, service.started);
        assertEquals(64, player.countItem("minecraft:stone"));
    }

    @Test
    void aHandoverThatDeliversNothingPausesWithTheExactShortfall() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer().creative(true);

        // Available, but the inventory is full of other things: nothing arrives.
        StubCreative full = new StubCreative(player, true, 0);

        BuildOutcome outcome = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                player, new StubSettings(true, false), null, full)
                .requestBuild(SCHEMATIC, ORIGIN);

        assertTrue(outcome.isPaused());
        assertEquals(0, service.started);
        assertEquals(0, player.countItem("minecraft:stone"), "no fake inventory");

        String report = String.join("\n", outcome.lines());
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

    // ------------------------------------------------------------------
    // Mid-build recovery - the live "Missing materials" pause scenario.
    // ------------------------------------------------------------------

    @Test
    void midBuildPauseIsSuppliedFromCreativeAndTheBuildContinues() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer().creative(true);
        StubCreative creative = new StubCreative(player, true);

        BuildCoordinator underTest = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                player, new StubSettings(true, false), null, creative);

        // The build starts from a full inventory - no Creative needed yet.
        player.set("minecraft:stone", 248);
        assertTrue(underTest.requestBuild(SCHEMATIC, ORIGIN).isStarted());
        assertEquals(0, creative.requests.size(),
                "nothing may be taken from Creative when the inventory already covers it");
        assertTrue(underTest.hasActiveBuild());

        // Mid-build the blocks are gone: this is what makes the engine pause
        // with "Missing materials for at least:".
        player.set("minecraft:stone", 0);

        BuildSupervisor.SupplyResult round = underTest.supplyOutstanding();

        assertEquals(248, round.delivered(), "the whole shortfall must be delivered");
        assertTrue(round.satisfied(),
                "the re-plan against the live inventory must confirm the requirement");
        assertEquals(248, player.countItem("minecraft:stone"),
                "the inventory count must actually increase");
        assertFalse(creative.requests.isEmpty(), "the Creative supplier must be called");
        assertTrue(underTest.lastCreativeReport().allSucceeded());
        assertEquals(248, underTest.lastCreativeReport().granted().get(0).acquired(),
                "the report must state what really arrived");
        assertEquals(1, service.started, "supplying must not restart the build");
    }

    @Test
    void midBuildSupplyNeverUsesCreativeWhenTheSettingIsOff() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer();
        StubCreative creative = new StubCreative(player, true);

        BuildCoordinator underTest = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                player, new StubSettings(false, false), null, creative);

        player.set("minecraft:stone", 248);
        assertTrue(underTest.requestBuild(SCHEMATIC, ORIGIN).isStarted());

        player.set("minecraft:stone", 0); // shortage appears mid-build

        BuildSupervisor.SupplyResult round = underTest.supplyOutstanding();

        assertEquals(0, round.delivered(), "with creative=false nothing may be supplied");
        assertFalse(round.satisfied(),
                "the shortage is still there, so the build must not be resumed");
        assertTrue(creative.requests.isEmpty(),
                "the Creative supplier must not even be contacted when the setting is off");
        assertEquals(0, player.countItem("minecraft:stone"));
    }

    @Test
    void midBuildSupplyReportsFailureWhenTheCreativeSourceCannotDeliver() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer().creative(true);
        StubCreative creative = new StubCreative(player, false); // unavailable

        BuildCoordinator underTest = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                player, new StubSettings(true, false), null, creative);

        player.set("minecraft:stone", 248);
        assertTrue(underTest.requestBuild(SCHEMATIC, ORIGIN).isStarted());

        player.set("minecraft:stone", 0);

        BuildSupervisor.SupplyResult round = underTest.supplyOutstanding();

        assertEquals(0, round.delivered(),
                "an unavailable supplier delivers nothing - the caller must be told");
        assertFalse(round.satisfied(),
                "nothing was supplied, so the requirement cannot be called satisfied");
        assertEquals(0, player.countItem("minecraft:stone"), "no fake inventory");
        assertTrue(creative.requests.isEmpty(), "an unavailable supplier is never asked");
        assertFalse(underTest.lastCreativeReport().allSucceeded(),
                "the failure must be reported, not swallowed");
        assertEquals(1, service.started, "a failed supply must not restart the build");
    }

    /**
     * Every material type must be satisfiable - one good type is not enough.
     *
     * <p>Planks arrive, dirt cannot (its slots are taken by other items), so
     * the round delivers items and is still <b>not</b> satisfied: the build must
     * stay paused rather than resume on a partial success. Once dirt can be
     * handed over, the round is satisfied and the build may continue.
     */
    @Test
    void everyMaterialTypeMustBeSatisfiedBeforeTheResultCountsAsSatisfied() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer().creative(true);

        // Planks fit; dirt has no room at all in this round.
        StubCreative creative = new StubCreative(player, true);
        creative.rejects("minecraft:dirt");

        BuildCoordinator underTest = coordinator(service, new StubAnalyzer(List.of(
                        need("minecraft:dirt", "Dirt", 100, 0, 0),
                        need("minecraft:oak_planks", "Oak Planks", 50, 0, 0))),
                player, new StubSettings(true, false), null, creative);

        player.set("minecraft:dirt", 100).set("minecraft:oak_planks", 50);
        assertTrue(underTest.requestBuild(SCHEMATIC, ORIGIN).isStarted());

        player.set("minecraft:dirt", 0).set("minecraft:oak_planks", 0);

        // Round 1: planks arrive, dirt cannot be obtained.
        BuildSupervisor.SupplyResult first = underTest.supplyOutstanding();

        assertEquals(50, first.delivered(), "the planks arrived");
        assertFalse(first.satisfied(),
                "one material being obtainable is not enough - dirt is still missing");
        assertEquals(50, player.countItem("minecraft:oak_planks"));
        assertEquals(0, player.countItem("minecraft:dirt"));
        assertEquals(List.of("Dirt x100"), underTest.remainingShortfalls(),
                "the report must name exactly the material still missing");

        // Round 2: dirt can now be handed over -> satisfied.
        creative.accepts("minecraft:dirt");
        BuildSupervisor.SupplyResult second = underTest.supplyOutstanding();

        assertTrue(second.delivered() > 0);
        assertTrue(second.satisfied(), "now every material is covered by the inventory");
        assertTrue(player.countItem("minecraft:dirt") > 0);
        assertTrue(underTest.remainingShortfalls().isEmpty());
        assertEquals(1, service.started, "supplying must not restart the build");
    }

    @Test
    void creativeAcquisitionNeverRunsWhenThePlayerIsNotActuallyInCreative() {
        RecordingBuildService service = new RecordingBuildService();

        // The setting says true, but the player is actually in survival.
        StubPlayer player = new StubPlayer();
        StubCreative creative = new StubCreative(player, true);

        BuildCoordinator underTest = coordinator(service,
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0))),
                player, new StubSettings(true, false), null, creative);

        player.set("minecraft:stone", 248);
        assertTrue(underTest.requestBuild(SCHEMATIC, ORIGIN).isStarted());

        player.set("minecraft:stone", 0);

        BuildSupervisor.SupplyResult round = underTest.supplyOutstanding();

        assertEquals(0, round.delivered(), "nothing may be invented outside Creative");
        assertFalse(round.satisfied());
        assertTrue(creative.requests.isEmpty(),
                "Creative acquisition must not even be attempted outside Creative");
        assertTrue(underTest.lastCreativeReport().failures().isEmpty(),
                "with Creative unusable no hand-over is reported at all");
        assertEquals(0, player.countItem("minecraft:stone"), "no fake inventory");
    }

    @Test
    void diagnosticsNameEveryMissingMaterialAndCountTheRealSlots() {
        RecordingBuildService service = new RecordingBuildService();
        StubPlayer player = new StubPlayer().creative(true);
        StubCreative creative = new StubCreative(player, true);
        creative.rejects("`$" + "minecraft:cobblestone");

        BuildCoordinator underTest = coordinator(service, new StubAnalyzer(List.of(
                        need("`$" + "minecraft:dirt", "Dirt", 100, 0, 0),
                        need("`$" + "minecraft:cobblestone", "Cobblestone", 64, 0, 0))),
                player, new StubSettings(true, false), null, creative);

        player.set("`$" + "minecraft:dirt", 100).set("`$" + "minecraft:cobblestone", 64);
        assertTrue(underTest.requestBuild(SCHEMATIC, ORIGIN).isStarted());

        player.set("`$" + "minecraft:dirt", 0).set("`$" + "minecraft:cobblestone", 0);
        underTest.supplyOutstanding();

        BuildCoordinator.Diagnostics diagnostics = underTest.diagnostics();

        assertTrue(diagnostics.hasBuild());
        assertTrue(diagnostics.creativeOn());
        assertTrue(diagnostics.actuallyInCreative());
        assertEquals(2, diagnostics.required());
        assertEquals(1, diagnostics.present(), "dirt is obtainable, cobblestone is not");
        assertEquals(1, diagnostics.missing());
        assertEquals(1, diagnostics.missingLines().size());
        assertTrue(diagnostics.missingLines().get(0).contains("minecraft:cobblestone"),
                diagnostics.missingLines().get(0));
    }

    @Test
    void diagnosticsWithoutABuildReportNoMaterials() {
        RecordingBuildService service = new RecordingBuildService();
        BuildCoordinator underTest = coordinator(service,
                new StubAnalyzer(List.of(need("`$" + "minecraft:stone", "Stone", 248, 0, 0))),
                new StubPlayer().creative(true), new StubSettings(true, false), null, null);

        BuildCoordinator.Diagnostics diagnostics = underTest.diagnostics();

        assertFalse(diagnostics.hasBuild());
        assertEquals(0, diagnostics.required());
        assertEquals(0, diagnostics.missing());
        assertTrue(diagnostics.creativeOn());
    }
// --- The outstanding count must not re-parse the schematic every tick. ---

    @Test
    void repeatedOutstandingReadsDoNotReanalyseTheSchematicEveryTime() {
        // A live log recorded 2,367 full re-analyses (and 16,569 log lines) in
        // about two minutes, because the movement watchdog reads the remaining
        // count on every client tick and each read re-parsed the whole file.
        RecordingBuildService service = new RecordingBuildService();
        StubAnalyzer analyzer =
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0)));
        // The player must actually hold the blocks, or the build is never
        // started and there is no active build to measure.
        BuildCoordinator underTest = coordinator(service, analyzer,
                new StubPlayer().set("minecraft:stone", 248),
                new StubSettings(false, false), null);

        assertTrue(underTest.requestBuild(SCHEMATIC, ORIGIN).isStarted(),
                "precondition: a build is running");
        analyzer.analyses = 0;

        int ticks = BuildCoordinator.OUTSTANDING_REFRESH_TICKS * 5;
        for (int i = 0; i < ticks; i++) {
            underTest.outstandingBlocks();
        }

        // One re-analysis per refresh window, not one per tick.
        int allowed = ticks / BuildCoordinator.OUTSTANDING_REFRESH_TICKS + 1;
        assertTrue(analyzer.analyses <= allowed,
                "expected at most " + allowed + " re-analyses over " + ticks
                        + " ticks, but got " + analyzer.analyses);
    }

    @Test
    void theOutstandingCountStillRefreshesAfterTheWindow() {
        RecordingBuildService service = new RecordingBuildService();
        StubAnalyzer analyzer =
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0)));
        BuildCoordinator underTest = coordinator(service, analyzer,
                new StubPlayer().set("minecraft:stone", 248),
                new StubSettings(false, false), null);

        assertTrue(underTest.requestBuild(SCHEMATIC, ORIGIN).isStarted(),
                "precondition: a build is running");
        int first = underTest.outstandingBlocks();

        // A block gets placed while the cached value is still warm.
        analyzer.needs.set(0, need("minecraft:stone", "Stone", 247, 0, 0));

        assertEquals(first, underTest.outstandingBlocks(),
                "the count is briefly cached, which is the whole point");

        for (int i = 0; i < BuildCoordinator.OUTSTANDING_REFRESH_TICKS; i++) {
            underTest.outstandingBlocks();
        }

        assertEquals(247, underTest.outstandingBlocks(),
                "after the refresh window the count must reflect the placed block");
    }

    @Test
    void invalidatingForcesAnImmediateRefresh() {
        RecordingBuildService service = new RecordingBuildService();
        StubAnalyzer analyzer =
                new StubAnalyzer(List.of(need("minecraft:stone", "Stone", 248, 0, 0)));
        BuildCoordinator underTest = coordinator(service, analyzer,
                new StubPlayer().set("minecraft:stone", 248),
                new StubSettings(false, false), null);

        assertTrue(underTest.requestBuild(SCHEMATIC, ORIGIN).isStarted(),
                "precondition: a build is running");
        assertEquals(248, underTest.outstandingBlocks());

        analyzer.needs.set(0, need("minecraft:stone", "Stone", 200, 0, 0));
        underTest.invalidateOutstanding();

        assertEquals(200, underTest.outstandingBlocks(),
                "an explicit invalidation must be honoured at once");
    }
}
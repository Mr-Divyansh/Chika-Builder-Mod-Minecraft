package dev.chika.builder.build.shop;

import dev.chika.builder.build.material.ItemAmount;
import dev.chika.builder.build.material.MaterialNeed;
import dev.chika.builder.build.material.MaterialPlan;
import dev.chika.builder.build.material.MaterialResolution;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests auto-shop purchasing, especially the rule that a purchase is only
 * believed once the inventory actually confirms it.
 */
class PurchaseOrchestratorTest {

    private static final String STONE = "minecraft:stone";

    /** Fake inventory so tests can simulate items arriving (or not). */
    private static final class FakeInventory implements PurchaseOrchestrator.InventoryCounter {

        private final Map<String, Integer> counts = new HashMap<>();

        FakeInventory put(String id, int count) {
            this.counts.put(id, count);
            return this;
        }

        @Override
        public int countOf(String itemId) {
            return this.counts.getOrDefault(itemId, 0);
        }
    }

    /** Records what was asked for, so tests can assert on the amounts. */
    private static final class RecordingAdapter implements ShopAdapter {

        private final boolean available;
        private final PurchaseResult result;
        private final boolean deliver;
        private final FakeInventory inventory;
        private ItemRequest lastRequest;

        RecordingAdapter(FakeInventory inventory, boolean available,
                         PurchaseResult result, boolean deliver) {
            this.inventory = inventory;
            this.available = available;
            this.result = result;
            this.deliver = deliver;
        }

        @Override
        public boolean isAvailable() {
            return this.available;
        }

        @Override
        public PurchaseResult purchase(ItemRequest request) {
            this.lastRequest = request;
            if (this.deliver) {
                // Simulate the server handing the items over.
                this.inventory.put(request.itemId(), this.inventory.countOf(request.itemId())
                        + request.amount());
            }
            return this.result;
        }

        @Override
        public String describe() {
            return "fake";
        }
    }

    /**
     * A need that the planner has routed to the shop.
     *
     * @param required total blocks of this type in the schematic
     * @param placed   already correct in the world
     * @param held     currently in the inventory
     */
    private static MaterialNeed shopNeed(int required, int placed, int held) {
        return new MaterialNeed(new ItemAmount(STONE, "Stone", required), required, placed, held,
                MaterialResolution.SHOP);
    }

    private static PurchaseOrchestrator orchestrator(ShopAdapter adapter,
                                                    PurchaseOrchestrator.InventoryCounter inv) {
        return new PurchaseOrchestrator(() -> Optional.ofNullable(adapter), inv);
    }

    @Test
    void nothingIsBoughtWhenNothingIsShort() {
        FakeInventory inventory = new FakeInventory();
        RecordingAdapter adapter = new RecordingAdapter(inventory, true,
                PurchaseResult.bought(0), true);

        // 248 required, 248 held -> shortfall is 0, so the shop is never called.
        PurchaseReport report = orchestrator(adapter, inventory)
                .fulfil(new MaterialPlan(List.of(shopNeed(248, 0, 248)), true));

        assertTrue(report.canContinue());
        org.junit.jupiter.api.Assertions.assertNull(adapter.lastRequest,
                "the shop must not be contacted when the inventory already covers it");
    }

    @Test
    void purchasesOnlyTheShortfall() {
        FakeInventory inventory = new FakeInventory();
        RecordingAdapter adapter = new RecordingAdapter(inventory, true,
                PurchaseResult.bought(48), true);

        // 248 required, 200 already built, 0 held -> only 48 are outstanding.
        PurchaseReport report = orchestrator(adapter, inventory)
                .fulfil(new MaterialPlan(List.of(shopNeed(248, 200, 0)), true));

        assertEquals(48, adapter.lastRequest.amount(),
                "must request only the shortfall, not the whole requirement");
        assertTrue(report.canContinue());
    }

    @Test
    void aPurchaseIsRejectedWhenTheItemsNeverArrive() {
        // Adapter claims success but delivers nothing -> must be a failure.
        FakeInventory inventory = new FakeInventory();
        RecordingAdapter adapter = new RecordingAdapter(inventory, true,
                PurchaseResult.bought(48), false);

        PurchaseReport report = orchestrator(adapter, inventory)
                .fulfil(new MaterialPlan(List.of(shopNeed(248, 0, 0)), true));

        assertFalse(report.canContinue(),
                "a purchase must not be trusted without inventory confirmation");
        assertEquals(1, report.failures().size());
        assertTrue(report.failures().get(0).reason().contains("did not deliver"));
    }

    @Test
    void insufficientMoneyPausesTheBuild() {
        FakeInventory inventory = new FakeInventory();
        RecordingAdapter adapter = new RecordingAdapter(inventory, true,
                PurchaseResult.failed("Insufficient money"), false);

        PurchaseReport report = orchestrator(adapter, inventory)
                .fulfil(new MaterialPlan(List.of(shopNeed(248, 0, 0)), true));

        assertFalse(report.canContinue());
        assertEquals("Insufficient money", report.failures().get(0).reason());
    }

    @Test
    void unavailableItemPausesTheBuild() {
        FakeInventory inventory = new FakeInventory();
        RecordingAdapter adapter = new RecordingAdapter(inventory, true,
                PurchaseResult.failed("Not sold on this server"), false);

        PurchaseReport report = orchestrator(adapter, inventory)
                .fulfil(new MaterialPlan(List.of(shopNeed(248, 0, 0)), true));

        assertFalse(report.canContinue());
        assertEquals("Not sold on this server", report.failures().get(0).reason());
    }

    @Test
    void noRegisteredShopFailsHonestlyRatherThanFakingIt() {
        FakeInventory inventory = new FakeInventory();

        PurchaseReport report = new PurchaseOrchestrator(() -> Optional.empty(), inventory)
                .fulfil(new MaterialPlan(List.of(shopNeed(248, 0, 0)), true));

        assertFalse(report.canContinue());
        assertEquals(1, report.failures().size(),
                "a missing shop must be reported, never silently ignored");
        assertTrue(report.failures().get(0).reason().contains("No shop"));
    }

    @Test
    void failureLinesReadLikeTheSpecifiedReport() {
        FakeInventory inventory = new FakeInventory();
        RecordingAdapter adapter = new RecordingAdapter(inventory, true,
                PurchaseResult.failed("Insufficient money"), false);

        PurchaseReport report = orchestrator(adapter, inventory)
                .fulfil(new MaterialPlan(List.of(shopNeed(248, 0, 0)), true));

        assertEquals(List.of("Stone x248 - Insufficient money"), report.describeFailures());
    }
}

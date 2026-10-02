package dev.chika.builder.build.material;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests taking blocks from Creative, especially the rule that a hand-over is
 * only believed once the inventory actually agrees.
 */
class CreativeAcquisitionTest {

    private static final String STONE = "minecraft:stone";

    /** Fake inventory so a test can decide whether items really arrived. */
    private static final class FakeInventory implements CreativeAcquisition.InventoryCounter {

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

    /**
     * A supplier that can claim a delivery without the inventory changing, which
     * is exactly the failure mode that must never start a build with materials
     * the player never had.
     */
    private static final class FakeSupplier implements CreativeSupplier {

        private final FakeInventory inventory;
        private final boolean available;
        /** How many of this item the inventory can hold in total (a fullness cap). */
        private final int cap;
        private final boolean deliver;
        private final List<String> requests = new ArrayList<>();

        FakeSupplier(FakeInventory inventory, boolean available, int cap, boolean deliver) {
            this.inventory = inventory;
            this.available = available;
            this.cap = cap;
            this.deliver = deliver;
        }

        @Override
        public boolean isAvailable() {
            return this.available;
        }

        @Override
        public int grant(String itemId, int amount) {
            this.requests.add(itemId + " x" + amount);

            // A cap models a full inventory: once the item is at capacity nothing
            // more fits, however many times the acquisition loop asks. This is
            // what makes repeated hand-overs stop instead of granting forever.
            int room = Math.max(0, this.cap - this.inventory.countOf(itemId));
            int claimed = Math.min(amount, room);

            if (this.deliver && claimed > 0) {
                this.inventory.put(itemId, this.inventory.countOf(itemId) + claimed);
            }

            return claimed;
        }

        @Override
        public String describe() {
            return "fake";
        }
    }

    private static MaterialNeed creativeNeed(int required, int placed, int held) {
        return new MaterialNeed(new ItemAmount(STONE, "Stone", required), required, placed, held,
                MaterialResolution.CREATIVE);
    }

    private static CreativeAcquisition acquisition(CreativeSupplier supplier,
                                                   CreativeAcquisition.InventoryCounter inv) {
        return new CreativeAcquisition(supplier, inv);
    }

    private static MaterialPlan plan(MaterialNeed... needs) {
        return new MaterialPlan(List.of(needs), false);
    }

    @Test
    void nothingIsTakenWhenNothingIsShort() {
        FakeInventory inventory = new FakeInventory();
        FakeSupplier supplier = new FakeSupplier(inventory, true, Integer.MAX_VALUE, true);

        // 248 required, 200 already placed, 248 held: nothing outstanding.
        CreativeReport report = acquisition(supplier, inventory)
                .acquire(plan(creativeNeed(248, 200, 248)));

        assertTrue(report.canContinue());
        assertTrue(supplier.requests.isEmpty(),
                "nothing may be handed over when nothing is short");
    }

    @Test
    void onlyTheShortfallIsRequested() {
        FakeInventory inventory = new FakeInventory();
        FakeSupplier supplier = new FakeSupplier(inventory, true, Integer.MAX_VALUE, true);

        // 248 required, 200 already built -> Creative owes 48, never 248.
        CreativeReport report = acquisition(supplier, inventory)
                .acquire(plan(creativeNeed(248, 200, 0)));

        assertEquals(List.of(STONE + " x48"), supplier.requests);
        assertTrue(report.canContinue());
        assertEquals(48, inventory.countOf(STONE));
    }

    @Test
    void anUnavailableCreativeSourceIsReportedHonestly() {
        FakeInventory inventory = new FakeInventory();
        FakeSupplier supplier = new FakeSupplier(inventory, false, Integer.MAX_VALUE, true);

        CreativeReport report = acquisition(supplier, inventory)
                .acquire(plan(creativeNeed(248, 0, 0)));

        assertFalse(report.canContinue());
        assertTrue(supplier.requests.isEmpty(),
                "an unavailable source must not be asked for blocks");
        assertEquals(1, report.failures().size());
        assertTrue(report.failures().get(0).reason().contains("not available"),
                report.failures().get(0).reason());
    }

    @Test
    void aHandoverThatDeliversNothingIsAFailure() {
        FakeInventory inventory = new FakeInventory();
        FakeSupplier supplier = new FakeSupplier(inventory, true, 0, true);

        CreativeReport report = acquisition(supplier, inventory)
                .acquire(plan(creativeNeed(248, 0, 0)));

        assertFalse(report.canContinue());
        assertTrue(report.failures().get(0).reason().contains("did not supply"),
                report.failures().get(0).reason());
    }

    @Test
    void theInventoryIsTheOnlyProofOfDelivery() {
        FakeInventory inventory = new FakeInventory();
        // Claims the full amount without the inventory ever changing.
        FakeSupplier supplier = new FakeSupplier(inventory, true, Integer.MAX_VALUE, false);

        CreativeReport report = acquisition(supplier, inventory)
                .acquire(plan(creativeNeed(248, 0, 0)));

        assertFalse(report.canContinue(),
                "a supplier claiming success must not be trusted on its own");
        assertEquals(0, inventory.countOf(STONE));
    }

    @Test
    void aPartialHandoverIsReportedWithTheExactNumbers() {
        FakeInventory inventory = new FakeInventory();
        FakeSupplier supplier = new FakeSupplier(inventory, true, 64, true);

        CreativeReport report = acquisition(supplier, inventory)
                .acquire(plan(creativeNeed(248, 0, 0)));

        assertFalse(report.canContinue());
        assertEquals(64, inventory.countOf(STONE));
        assertEquals(1, report.granted().size());
        assertEquals(64, report.granted().get(0).acquired());
        assertEquals(248, report.granted().get(0).requested());
        assertEquals(List.of("Stone x248 - Only 64 of 248 fitted in the inventory."),
                report.describeFailures());
    }

    @Test
    void failureLinesReadLikeThePauseReport() {
        FakeInventory inventory = new FakeInventory();
        FakeSupplier supplier = new FakeSupplier(inventory, true, 0, true);

        CreativeReport report = acquisition(supplier, inventory)
                .acquire(plan(creativeNeed(248, 0, 0)));

        assertEquals(List.of("Stone x248 - Creative did not supply these blocks."),
                report.describeFailures());
    }
}
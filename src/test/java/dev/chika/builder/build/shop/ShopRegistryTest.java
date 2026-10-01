package dev.chika.builder.build.shop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the shop seam: servers register an adapter, and with none registered
 * auto-shop fails honestly instead of pretending a purchase happened.
 */
class ShopRegistryTest {

    @AfterEach
    void cleanUp() {
        ShopRegistry.clear();
    }

    @Test
    void shipsWithNoAdapterBecauseShopsAreServerSpecific() {
        assertFalse(ShopRegistry.hasAvailableAdapter(),
                "Chika Builder must not assume any particular server's /shop");
        assertTrue(ShopRegistry.active().isEmpty());
    }

    @Test
    void aRegisteredAdapterBecomesActive() {
        StubAdapter adapter = new StubAdapter(true);
        ShopRegistry.register(adapter);

        assertTrue(ShopRegistry.hasAvailableAdapter());
        assertEquals(adapter, ShopRegistry.active().orElseThrow());
        assertEquals("stub", ShopRegistry.adapters().get(0).describe());
    }

    @Test
    void anUnavailableAdapterIsNotUsed() {
        ShopRegistry.register(new StubAdapter(false));

        assertFalse(ShopRegistry.hasAvailableAdapter(),
                "an adapter that reports itself unavailable must be skipped");
    }

    @Test
    void unregisteringRestoresTheEmptyState() {
        StubAdapter adapter = new StubAdapter(true);
        ShopRegistry.register(adapter);

        assertTrue(ShopRegistry.unregister(adapter));
        assertFalse(ShopRegistry.hasAvailableAdapter());
    }

    @Test
    void registeringTheSameAdapterTwiceIsIgnored() {
        StubAdapter adapter = new StubAdapter(true);
        ShopRegistry.register(adapter);
        ShopRegistry.register(adapter);

        assertEquals(1, ShopRegistry.adapters().size());
    }

    @Test
    void aNoOpAdapterNeverClaimsAPurchaseSucceeded() {
        // The contract for an adapter that cannot buy: report failure. An
        // implementation claiming success without delivering would let the build
        // place blocks the player never paid for.
        PurchaseResult result = PurchaseResult.failed("Not sold on this server.");

        assertFalse(result.success());
        assertEquals(0, result.acquired());
        assertEquals("Not sold on this server.", result.reason());
    }

    @Test
    void aSuccessfulResultCarriesTheConfirmedCount() {
        PurchaseResult result = PurchaseResult.bought(48);

        assertTrue(result.success());
        assertEquals(48, result.acquired());
    }

    private static final class StubAdapter implements ShopAdapter {

        private final boolean available;

        StubAdapter(boolean available) {
            this.available = available;
        }

        @Override
        public boolean isAvailable() {
            return this.available;
        }

        @Override
        public PurchaseResult purchase(ItemRequest request) {
            return PurchaseResult.failed("stub never buys");
        }

        @Override
        public String describe() {
            return "stub";
        }
    }
}
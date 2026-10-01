package dev.chika.builder.build.shop;

import dev.chika.builder.build.material.ItemAmount;
import dev.chika.builder.build.material.MaterialNeed;
import dev.chika.builder.build.material.MaterialPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Buys the materials a {@link MaterialPlan} marked as {@code SHOP}, then
 * verifies each purchase actually landed in the inventory.
 *
 * <p>The verification step is the important one. An adapter reporting success is
 * never trusted on its own: after each purchase the inventory is re-counted, and
 * an item is only treated as acquired if the count actually increased. A server
 * that silently rejects a purchase, charges without delivering, or delivers
 * late therefore produces a reported failure instead of a build that places
 * blocks the player never paid for.
 *
 * <p>Pure and dependency-injected, so every branch is unit testable without a
 * running game.
 */
public final class PurchaseOrchestrator {

    private final ShopRegistryLookup registry;
    private final InventoryCounter inventory;

    /**
     * @param registry  which adapter to use, and whether one exists at all
     * @param inventory reads the player's current count of an item id; injected
     *                  so this class stays free of Minecraft types
     */
    public PurchaseOrchestrator(ShopRegistryLookup registry, InventoryCounter inventory) {
        this.registry = registry;
        this.inventory = inventory;
    }

    /** Narrow view of {@link ShopRegistry} so tests can inject a stub. */
    public interface ShopRegistryLookup {
        Optional<ShopAdapter> active();
    }

    /** Reads how many of an item the player is currently holding. */
    public interface InventoryCounter {
        int countOf(String itemId);
    }

    /** Convenience constructor using the real static registry. */
    public static PurchaseOrchestrator usingRealRegistry(InventoryCounter inventory) {
        return new PurchaseOrchestrator(ShopRegistry::active, inventory);
    }

    /**
     * Buys everything the plan still needs.
     *
     * @return which purchases succeeded and which failed; never {@code null}
     */
    public PurchaseReport fulfil(MaterialPlan plan) {
        List<MaterialNeed> wanted = plan.toPurchase();

        if (wanted.isEmpty()) {
            return PurchaseReport.nothingToDo();
        }

        Optional<ShopAdapter> adapter = this.registry.active();

        // No shop implementation is registered for this server. Report that
        // honestly instead of pretending the items were bought.
        if (adapter.isEmpty()) {
            return allFailed(wanted, "No shop is available on this server.");
        }

        ShopAdapter shop = adapter.get();
        List<PurchaseResult> purchased = new ArrayList<>();
        List<PurchaseReport.PurchaseFailure> failures = new ArrayList<>();

        for (MaterialNeed need : wanted) {
            PurchaseResult result = buyOne(shop, need);

            if (result.success()) {
                purchased.add(result);
            } else {
                failures.add(new PurchaseReport.PurchaseFailure(requestFor(need), result.reason()));
            }
        }

        return new PurchaseReport(purchased, failures);
    }

    /**
     * Buys one block type and confirms the items arrived.
     *
     * <p>Only the <em>shortfall</em> is requested - never the whole requirement -
     * so blocks already in the world and items already in the inventory are never
     * re-bought.
     */
    private PurchaseResult buyOne(ShopAdapter shop, MaterialNeed need) {
        int wanted = need.shortfall();

        if (wanted <= 0) {
            // Nothing outstanding once existing blocks and inventory are counted.
            return PurchaseResult.bought(0);
        }

        ItemRequest request = requestFor(need);
        int before = countOf(need.item().itemId());

        PurchaseResult result = shop.purchase(request);

        if (!result.success()) {
            return PurchaseResult.failed(result.reason());
        }

        // Do NOT trust the adapter: confirm the items really arrived.
        int after = countOf(need.item().itemId());
        int gained = Math.max(0, after - before);

        if (gained <= 0) {
            return PurchaseResult.failed("The shop did not deliver " + request.describe() + ".");
        }

        return PurchaseResult.bought(gained);
    }

    /** Only the shortfall of a need, never the full schematic requirement. */
    private static ItemRequest requestFor(MaterialNeed need) {
        return ItemRequest.of(new ItemAmount(
                need.item().itemId(), need.item().displayName(), Math.max(0, need.shortfall())));
    }

    private int countOf(String itemId) {
        try {
            return this.inventory.countOf(itemId);
        } catch (Throwable t) {
            // If the count cannot be read, treat it as zero so verification fails
            // and the build pauses rather than continuing unchecked.
            return 0;
        }
    }

    private static PurchaseReport allFailed(List<MaterialNeed> wanted, String reason) {
        List<PurchaseReport.PurchaseFailure> failures = new ArrayList<>(wanted.size());
        for (MaterialNeed need : wanted) {
            failures.add(new PurchaseReport.PurchaseFailure(requestFor(need), reason));
        }
        return new PurchaseReport(List.of(), failures);
    }
}


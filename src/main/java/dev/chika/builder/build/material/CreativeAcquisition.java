package dev.chika.builder.build.material;

import java.util.ArrayList;
import java.util.List;

/**
 * Takes the materials a {@link MaterialPlan} routed to Creative, then verifies
 * each hand-over actually landed in the player's inventory.
 *
 * <p>The verification step is the important one, and it is the reason Creative
 * building works at all: a plan saying "Creative will supply this" is not the
 * same as the player holding the blocks. After each hand-over the inventory is
 * re-counted, and a material only counts as acquired when the count really went
 * up. A gamemode that changed underneath us, an inventory with no room left, or
 * a hand-over the game declined therefore produces a reported failure instead of
 * a build that places blocks the player never had.
 *
 * <p>Pure and dependency-injected, so every branch is unit testable without a
 * running game.
 */
public final class CreativeAcquisition {

    private final CreativeSupplier supplier;
    private final InventoryCounter inventory;

    /**
     * @param supplier  how blocks are taken from Creative
     * @param inventory reads the player's current count of an item id; injected
     *                  so this class stays free of Minecraft types
     */
    public CreativeAcquisition(CreativeSupplier supplier, InventoryCounter inventory) {
        this.supplier = supplier;
        this.inventory = inventory;
    }

    /** Reads how many of an item the player is currently holding. */
    public interface InventoryCounter {
        int countOf(String itemId);
    }

    /**
     * Supplies everything the plan still needs from Creative.
     *
     * @return what was actually handed over and what was not; never {@code null}
     */
    public CreativeReport acquire(MaterialPlan plan) {
        List<MaterialNeed> wanted = plan.toAcquireCreatively();

        if (wanted.isEmpty()) {
            return CreativeReport.nothingToDo();
        }

        // Never take anything unless the player genuinely is in Creative. The
        // setting alone is a permission, not a fact.
        if (!isAvailable()) {
            return allFailed(wanted, "Creative mode is not available.");
        }

        List<CreativeReport.CreativeGrant> granted = new ArrayList<>();
        List<CreativeReport.CreativeFailure> failures = new ArrayList<>();

        for (MaterialNeed need : wanted) {
            int requested = need.shortfall();

            if (requested <= 0) {
                // Nothing outstanding once placed blocks and holdings are counted.
                continue;
            }

            String itemId = need.item().itemId();
            String displayName = need.item().displayName();

            int before = countOf(itemId);
            grant(itemId, requested);
            int after = countOf(itemId);

            int gained = Math.max(0, after - before);

            if (gained <= 0) {
                failures.add(new CreativeReport.CreativeFailure(itemId, displayName, requested,
                        "Creative did not supply these blocks."));
                continue;
            }

            granted.add(new CreativeReport.CreativeGrant(itemId, displayName, requested, gained));

            if (gained < requested) {
                // Be exact: the player must know why the build still cannot start.
                failures.add(new CreativeReport.CreativeFailure(itemId, displayName, requested,
                        "Only " + gained + " of " + requested + " fitted in the inventory."));
            }
        }

        return new CreativeReport(granted, failures);
    }

    private boolean isAvailable() {
        try {
            return this.supplier.isAvailable();
        } catch (Throwable t) {
            // A supplier that cannot answer must never be assumed to work.
            return false;
        }
    }

    private void grant(String itemId, int amount) {
        try {
            this.supplier.grant(itemId, amount);
        } catch (Throwable ignored) {
            // The count below decides whether anything actually arrived.
        }
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

    private static CreativeReport allFailed(List<MaterialNeed> wanted, String reason) {
        List<CreativeReport.CreativeFailure> failures = new ArrayList<>(wanted.size());
        for (MaterialNeed need : wanted) {
            failures.add(new CreativeReport.CreativeFailure(
                    need.item().itemId(), need.item().displayName(),
                    Math.max(0, need.shortfall()), reason));
        }
        return new CreativeReport(List.of(), failures);
    }
}
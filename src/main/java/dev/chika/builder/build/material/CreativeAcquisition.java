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

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Chika Builder");

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
            LOGGER.info("[Chika Builder] Creative supply unavailable - "
                    + "{} material type(s) not handed over (player is not in "
                    + "Creative, or there is no player yet).", wanted.size());
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
            int inventoryBefore = countOf(itemId);

            // Creative is infinite, but the player's inventory is not: hand over
            // repeatedly until the whole shortfall arrives, the inventory is
            // full, or a grant makes no further progress.
            //
            // A single call is NOT enough. The inventory supplier fills at most
            // one stack per call, so asking once for 248 stone could only ever
            // produce 64 and the build would stall on an artificially small
            // number. The loop is bounded by the shortfall itself, so it can
            // never run forever: each pass either closes the gap or stops.
            int gained = 0;

            while (gained < requested) {
                int before = countOf(itemId);
                int stillNeeded = requested - gained;

                grant(itemId, stillNeeded);

                int after = countOf(itemId);
                int step = Math.max(0, after - before);

                if (step <= 0) {
                    // Nothing arrived. Either the inventory is full or Creative is
                    // no longer available; either way, stop and report honestly.
                    break;
                }

                gained += step;
            }

            if (gained <= 0) {
                LOGGER.info("[Chika Builder] Creative supply {}: required={}, "
                                + "inventory before={}, supplied=0, remaining={}",
                        itemId, requested, inventoryBefore, requested);
                failures.add(new CreativeReport.CreativeFailure(itemId, displayName, requested,
                        "Creative did not supply these blocks."));
                continue;
            }

            LOGGER.info("[Chika Builder] Creative supply {}: required={}, "
                            + "inventory before={}, supplied={}, inventory after={}, "
                            + "remaining={}",
                    itemId, requested, inventoryBefore, gained, countOf(itemId),
                    Math.max(0, requested - gained));

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
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

    /**
     * How many hand-over calls a single material may get in one round.
     *
     * <p>One is normally enough (a Creative hand-over fills a whole stack at
     * once); a second covers a supplier that needs a follow-up call. The loop
     * also stops the moment a call makes no progress, so this is a hard ceiling,
     * never a loop that can spin.
     */
    private static final int MAX_PRESENCE_PASSES = 2;

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

            // Creative is an effectively unlimited source, and the engine's own
            // material check only asks whether the block is PRESENT in the
            // player's 36 storage slots (it builds a placeable list by scanning
            // them, and in Creative placing does not consume the stack). So the
            // goal of a round is presence, not the full total: insisting on all
            // 248 dirt is what made a schematic unsatisfiable once the inventory
            // had to share its slots between material types.
            //
            // The loop is bounded: a pass either makes the item present or makes
            // no progress, and no progress means we stop and report honestly.
            int gained = 0;
            boolean present = inventoryBefore > 0;

            for (int pass = 0; pass < MAX_PRESENCE_PASSES && !present; pass++) {
                int passBefore = countOf(itemId);

                grant(itemId, requested - gained);

                int passAfter = countOf(itemId);
                int step = Math.max(0, passAfter - passBefore);

                gained += step;
                present = passAfter > 0;

                if (step <= 0) {
                    // Nothing arrived: the inventory is full of other items, or
                    // Creative is no longer available. Either way, stop.
                    break;
                }
            }

            if (!present) {
                LOGGER.info("[Chika Builder] Creative supply {}: required={}, "
                                + "inventory before={}, supplied=0, remaining={}, present=false",
                        itemId, requested, inventoryBefore, requested);
                failures.add(new CreativeReport.CreativeFailure(itemId, displayName, requested,
                        "Creative did not supply these blocks."));
                continue;
            }

            LOGGER.info("[Chika Builder] Creative supply {}: required={}, "
                            + "inventory before={}, supplied={}, inventory after={}, present=true",
                    itemId, requested, inventoryBefore, gained, countOf(itemId));

            granted.add(new CreativeReport.CreativeGrant(itemId, displayName, requested, gained));
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
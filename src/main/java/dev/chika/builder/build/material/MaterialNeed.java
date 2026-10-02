package dev.chika.builder.build.material;

/**
 * One block type required by a schematic, and how it will be obtained.
 *
 * @param item        what is needed
 * @param required    total blocks of this type in the schematic
 * @param alreadyPlaced how many are already correct in the world
 * @param inInventory how many the player is currently holding
 * @param resolution  which rung of the priority ladder satisfied it
 */
public record MaterialNeed(ItemAmount item, int required, int alreadyPlaced,
                           int inInventory, MaterialResolution resolution) {

    public MaterialNeed {
        if (required < 0 || alreadyPlaced < 0 || inInventory < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }

    /**
     * Blocks that still have to be placed, i.e. what is not already in the world.
     *
     * <p>This is the number the shop should buy and the number compared against
     * the inventory - NOT {@link #required()}, which double-counts work that is
     * already done and would cause unnecessary purchases.
     */
    public int outstanding() {
        return Math.max(0, this.required - this.alreadyPlaced);
    }

    /** How many more items must be acquired beyond what the player holds. */
    public int shortfall() {
        return Math.max(0, this.outstanding() - this.inInventory);
    }

    /**
     * True when nothing further has to be acquired: the world already has it or
     * the player is holding it.
     *
     * <p>{@link MaterialResolution#CREATIVE} and {@link MaterialResolution#SHOP}
     * are deliberately <b>not</b> satisfied. Both are <i>plans</i>, not holdings:
     * the blocks still have to be handed over or bought, and treating either as
     * done would start a build with materials the player does not actually have.
     */
    public boolean isSatisfied() {
        return this.resolution == MaterialResolution.ALREADY_PLACED
                || this.resolution == MaterialResolution.INVENTORY;
    }

    /**
     * True when nothing blocks the build outright (i.e. it is not unobtainable).
     *
     * <p>Weaker than {@link #isSatisfied()}: a planned purchase is "not missing"
     * but still has to happen first.
     */
    public boolean isCovered() {
        return this.resolution() != MaterialResolution.MISSING;
    }

    /** {@code "Stone x248"} using the outstanding (not total) count. */
    public String describeOutstanding() {
        return this.item().displayName() + " x" + this.outstanding();
    }

    /** {@code "Stone x248"}, using the total required count. */
    public String describeRequired() {
        return this.item().describe();
    }
}

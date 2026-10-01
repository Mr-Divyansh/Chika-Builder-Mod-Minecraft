package dev.chika.builder.build.material;

/**
 * An amount of one specific item, identified by its resource id.
 *
 * <p>Deliberately free of any Minecraft type ({@code ItemStack} and friends) so
 * the material logic can be unit tested without a running game. The Minecraft
 * layer converts to and from this type at the boundary.
 *
 * @param itemId     resource id, e.g. {@code minecraft:stone}
 * @param displayName player-facing label, e.g. {@code Stone}
 * @param amount     how many are needed
 */
public record ItemAmount(String itemId, String displayName, int amount) {

    public ItemAmount {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = itemId;
        }
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        }
    }

    /** Convenience for a single unit. */
    public static ItemAmount of(String itemId, String displayName) {
        return new ItemAmount(itemId, displayName, 1);
    }

    /** True when nothing is required. */
    public boolean isEmpty() {
        return this.amount == 0;
    }

    /** {@code "Stone x248"}, the form used in player-facing reports. */
    public String describe() {
        return this.displayName + " x" + this.amount;
    }
}

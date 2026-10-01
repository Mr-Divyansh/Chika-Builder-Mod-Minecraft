package dev.chika.builder.build.shop;

import dev.chika.builder.build.material.ItemAmount;

/**
 * One "buy N of this item" request handed to a {@link ShopAdapter}.
 *
 * @param itemId resource id, e.g. {@code minecraft:stone}
 * @param displayName player-facing label, e.g. {@code Stone}
 * @param amount how many to buy
 */
public record ItemRequest(String itemId, String displayName, int amount) {

    public ItemRequest {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
    }

    /** Builds a request from a planned {@link ItemAmount}. */
    public static ItemRequest of(ItemAmount amount) {
        return new ItemRequest(amount.itemId(), amount.displayName(), amount.amount());
    }

    /** {@code "Stone x248"} for player-facing failure reports. */
    public String describe() {
        return displayName + " x" + amount;
    }
}

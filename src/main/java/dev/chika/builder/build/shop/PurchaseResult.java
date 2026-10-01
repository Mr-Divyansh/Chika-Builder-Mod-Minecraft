package dev.chika.builder.build.shop;

/**
 * Outcome of a single {@link ShopAdapter#purchase(ItemRequest)} call.
 *
 * <p>A success means the items were <em>confirmed present in the player's
 * inventory afterwards</em>. An adapter must never report success on the basis
 * of a command being sent: the server can reject it, the player can be too poor,
 * or the item can be out of stock.
 *
 * @param success  true only when the items were actually acquired
 * @param acquired how many were confirmed acquired
 * @param reason   why it failed, or the success note; player-facing, so it must
 *                 read naturally and never contain third-party mod branding
 */
public record PurchaseResult(boolean success, int acquired, String reason) {

    private static final String OK = "Purchased.";

    /**
     * The items were bought and verified in the inventory.
     *
     * @param acquired must be the confirmed count, not the requested count
     */
    public static PurchaseResult bought(int acquired) {
        return new PurchaseResult(true, acquired, OK);
    }

    /**
     * The purchase did not happen.
     *
     * <p>The reason is shown to the player verbatim, so implementations should
     * say something specific such as "Insufficient money" or
     * "Not sold on this server".
     */
    public static PurchaseResult failed(String reason) {
        String safe = (reason == null || reason.isBlank()) ? "Purchase failed." : reason;
        return new PurchaseResult(false, 0, safe);
    }
}

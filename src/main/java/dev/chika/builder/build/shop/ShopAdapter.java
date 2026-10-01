package dev.chika.builder.build.shop;

/**
 * Seam for a server's shop system.
 *
 * <p>Every server implements {@code /shop} differently - a chat command, a GUI,
 * an NPC, or nothing at all - so there is no single implementation that can be
 * correct everywhere. Implementations therefore register themselves (see
 * {@code ShopRegistry}) and Chika Builder works with whichever one the server
 * provides.
 *
 * <p><b>Safety rules every implementation must follow:</b>
 * <ul>
 *   <li>Use only normal, legitimate interactions the player's own client would
 *       perform: real commands, real clicks, real packets the vanilla client
 *       already sends.</li>
 *   <li>Never bypass anti-cheat, never forge packets, never abuse a race
 *       condition, and never touch any mechanic the server has not exposed to
 *       ordinary players.</li>
 *   <li>Report failure honestly. Returning success without actually acquiring
 *       the item would let the build place blocks the player never paid for,
 *       so an implementation that cannot confirm a purchase MUST return
 *       {@link PurchaseResult#failed}.</li>
 * </ul>
 */
public interface ShopAdapter {

    /**
     * Whether this adapter can currently shop on the connected server.
     *
     * <p>Cheap, and called before every purchase so that leaving a shop server
     * correctly disables auto-shopping.
     */
    boolean isAvailable();

    /**
     * Attempts to buy {@code amount} of one item.
     *
     * <p>Implementations must verify the items were actually received before
     * reporting success.
     *
     * @return the outcome; never {@code null}
     */
    PurchaseResult purchase(ItemRequest request);

    /**
     * Short player-facing label for this adapter, e.g. {@code "chat command"}.
     * Used in diagnostics only; must not contain third-party mod branding.
     */
    String describe();
}

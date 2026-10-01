package dev.chika.builder.build.material;

/**
 * How a required material will be obtained, in the order Chika Builder tries.
 *
 * <p>The ordinal order IS the priority ladder (see {@link MaterialPlanner}):
 * <ol>
 *   <li>{@link #ALREADY_PLACED} - the world already satisfies this, so nothing
 *       is needed and nothing is spent;</li>
 *   <li>{@link #INVENTORY} - the player already holds it;</li>
 *   <li>{@link #CREATIVE} - infinite supply, but ONLY when the player is
 *       genuinely in Creative (never assumed);</li>
 *   <li>{@link #SHOP} - purchased through a registered {@code ShopAdapter};</li>
 *   <li>{@link #MISSING} - unobtainable, so the build pauses.</li>
 * </ol>
 */
public enum MaterialResolution {

    /** A correct block is already in the world; it must not be re-supplied. */
    ALREADY_PLACED,

    /** Available in the player's inventory right now. */
    INVENTORY,

    /** Supplied by Creative mechanics because the player is really in Creative. */
    CREATIVE,

    /** To be bought by a registered shop adapter. */
    SHOP,

    /** Not obtainable through any enabled method - the build must pause. */
    MISSING
}

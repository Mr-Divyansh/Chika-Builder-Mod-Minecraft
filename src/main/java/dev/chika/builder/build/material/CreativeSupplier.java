package dev.chika.builder.build.material;

/**
 * Supplies blocks straight from Creative mode.
 *
 * <p>Creative is the one rung of the supply ladder where the blocks are not
 * already somewhere - they have to be handed to the player. This interface is
 * that hand-over, and it exists as a seam so the material planner can be tested
 * without a running game.
 *
 * <p><b>Safety rules every implementation must follow:</b>
 * <ul>
 *   <li>Only work when the player is <em>genuinely</em> in Creative. The
 *       permission setting alone must never be enough.</li>
 *   <li>Never change the player's gamemode, and never touch anything the game
 *       itself would not do when picking a block from the Creative inventory.</li>
 *   <li>Report the truth. {@link #grant} returns how many items really reached
 *       the player's inventory; an implementation that cannot confirm the
 *       hand-over must return {@code 0} rather than a hopeful number.</li>
 * </ul>
 */
public interface CreativeSupplier {

    /**
     * Whether blocks can be taken from Creative right now.
     *
     * <p>Cheap, and called before every hand-over. {@code false} when the
     * player is not actually in Creative.
     */
    boolean isAvailable();

    /**
     * Hands up to {@code amount} of {@code itemId} to the player.
     *
     * @param itemId resource id, e.g. {@code minecraft:stone}
     * @param amount how many are wanted
     * @return how many were actually placed in the player's inventory; {@code 0}
     *         when nothing could be supplied
     */
    int grant(String itemId, int amount);

    /** Short player-facing label, e.g. {@code "creative"}. Diagnostics only. */
    String describe();

    /** A supplier used when no world is loaded; never supplies anything. */
    CreativeSupplier NONE = new CreativeSupplier() {

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public int grant(String itemId, int amount) {
            return 0;
        }

        @Override
        public String describe() {
            return "unavailable";
        }
    };
}
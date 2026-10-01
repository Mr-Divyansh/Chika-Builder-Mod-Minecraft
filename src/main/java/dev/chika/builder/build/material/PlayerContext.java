package dev.chika.builder.build.material;

/**
 * Reads the real gamemode state and inventory of the local player.
 *
 * <p>Abstracted so the material planner and purchase verifier can be tested
 * without a running game, and so the "is the player ACTUALLY in Creative"
 * question has exactly one implementation.
 */
public interface PlayerContext {

    /**
     * The player's <em>actual</em> gamemode as reported by the client.
     *
     * <p>Chika Builder never changes this - it only reads it. Creative
     * mechanics are used only when this returns {@code true}.
     */
    boolean isActuallyInCreative();

    /** How many of {@code itemId} the player is currently holding. */
    int countItem(String itemId);

    /** A context used before a world is loaded; reports no materials. */
    PlayerContext NONE = new PlayerContext() {
        @Override
        public boolean isActuallyInCreative() {
            return false;
        }

        @Override
        public int countItem(String itemId) {
            return 0;
        }
    };
}

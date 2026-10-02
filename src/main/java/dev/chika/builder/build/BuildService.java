package dev.chika.builder.build;

import java.io.File;

/**
 * Abstraction over "something that can construct a schematic in the world".
 *
 * <p>This is the seam that keeps Chika Builder's command layer independent of
 * the internal build engine. The command layer only ever talks to this
 * interface, so the engine-backed implementation can be swapped for a
 * standalone implementation later without touching {@code #chika_build} or any
 * user-visible behaviour.
 */
public interface BuildService {

    /** Human readable name of this backend, used in chat feedback. */
    String name();

    /** True when the backend is available and able to accept a build request right now. */
    boolean isAvailable();

    /**
     * Starts building {@code schematic} with its corner anchored at
     * {@code origin}.
     *
     * @param schematic schematic file to construct
     * @param origin    world position of the schematic's (0,0,0) corner
     * @throws BuildException if the schematic cannot be read or the build
     *                        cannot be started
     */
    void startBuild(File schematic, Origin origin) throws BuildException;

    /** True while a build requested through this service is still running. */
    boolean isBuilding();

    /**
     * True when a running build has paused itself and is waiting.
     *
     * <p>The backend can stop mid-way when it cannot find the blocks it needs
     * next. That is not the same as the build ending, so it has to be
     * distinguishable from {@link #isBuilding()} - otherwise a paused build is
     * indistinguishable from a finished one.
     */
    boolean isPaused();

    /**
     * Continues a paused build from where it stopped.
     *
     * <p>Used after missing material has been supplied, so already-placed blocks
     * are kept and only the remaining work continues.
     */
    void resume();

    /**
     * True when the backend is actively moving the player or calculating a
     * path right now.
     *
     * <p>Used by the movement watchdog to tell a genuine stall from ordinary
     * work: a long path calculation is invisible from outside and looks
     * identical to standing still.
     *
     * <p>Defaults to {@code false}, so a backend that cannot report this simply
     * never claims to be pathing.
     */
    default boolean isPathing() {
        return false;
    }

    /** The backend's current movement goal, for stall diagnostics. */
    default String describeGoal() {
        return "unavailable";
    }

    /**
     * A goal identity that changes when a genuinely <b>different</b> target is
     * chosen, used to tell real re-planning from the engine handing back the
     * same target it already had.
     *
     * <p>Defaults to {@link #describeGoal()} so a backend that cannot report
     * more still works, just with a weaker signal.
     */
    default String describeGoalIdentity() {
        return describeGoal();
    }

    /**
     * Where the backend's current path is heading, or a sentinel when it has
     * none. A second progress signal for the movement watchdog.
     */
    default String describePathDestination() {
        return "none";
    }

    /**
     * Phase 1 of a re-plan: asks the backend to stop acting on its current path.
     *
     * <p>Paired with {@link #finishRepath()} on a later tick. The engine only
     * discards a stale path when it observes the paused state on one of its own
     * ticks, so the two must not be collapsed into a single call.
     *
     * @return true if the request was accepted
     */
    default boolean beginRepath() {
        return false;
    }

    /**
     * Phase 2 of a re-plan: lets the backend act again so it re-plans.
     *
     * <p>Implementations must keep the build and any already-placed blocks. They
     * must not teleport, nudge or otherwise move the player, and must not bypass
     * collision or pathfinding.
     */
    default void finishRepath() {
        // No safe recovery available.
    }

    /**
     * Cancels any in-flight build started through this service.
     *
     * @return true if a running build was cancelled
     */
    boolean cancel();

    /** Simple mutable description of the block a build should be anchored to. */
    final class Origin {

        private final int x;
        private final int y;
        private final int z;

        public Origin(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int x() {
            return this.x;
        }

        public int y() {
            return this.y;
        }

        public int z() {
            return this.z;
        }

        @Override
        public String toString() {
            return "(" + this.x + ", " + this.y + ", " + this.z + ")";
        }
    }
}
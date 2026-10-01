package dev.chika.builder.build;

import java.io.File;

/**
 * Abstraction over "something that can construct a schematic in the world".
 *
 * <p>This is the seam that keeps Chika Builder's command layer independent of
 * Baritone. The command layer only ever talks to this interface, so the
 * Baritone-backed implementation can be swapped for a standalone implementation
 * later without touching {@code #chika_build} or any user-visible behaviour.
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
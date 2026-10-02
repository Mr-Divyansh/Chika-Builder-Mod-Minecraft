package dev.chika.builder.build;

/**
 * Keeps a running build alive after Chika Builder has handed it to the engine.
 *
 * <p><b>Why this exists.</b> The build engine runs the placement itself and it
 * stops on its own the moment it cannot find the blocks it needs next - it
 * pauses and prints its own {@code "Missing materials for at least:"} and
 * {@code "Unable to do it. Pausing."} lines. Before this class existed, Chika
 * Builder supplied materials once, started the build, and then never looked
 * again: the build sat paused forever, with the engine telling the player to
 * type {@code resume} - a command this mod deliberately removes. The result was
 * a build that visibly stopped partway through and could not be recovered.
 *
 * <p>What this class does, on each tick:
 * <ol>
 *   <li>If the engine has finished, verify the world against the schematic and
 *       report {@code COMPLETE} only when nothing is left outstanding.</li>
 *   <li>If the engine is paused and material is still short, ask the supply
 *       chain for more and {@code resume} the engine, so the build continues
 *       instead of stalling.</li>
 *   <li>If supplying cannot make progress, give up after a bounded number of
 *       attempts and report {@code PAUSED} with the exact shortfall.</li>
 * </ol>
 *
 * <p>Pure decision logic: every engine interaction goes through the injected
 * seams below, so each branch is unit testable without a running game.
 */
public final class BuildSupervisor {

    /** How many consecutive fruitless supply rounds are allowed before pausing. */
    public static final int MAX_SUPPLY_ATTEMPTS = 5;

    /** What the supervisor believes is happening to the build. */
    public enum Status {
        /** No build running. */
        IDLE,
        /** The engine is placing blocks. */
        RUNNING,
        /** Supply could not continue the build; the player must act. */
        PAUSED,
        /** Every required block was verified correct. */
        COMPLETE
    }

    /** The engine, reduced to what the supervisor needs from it. */
    public interface BuildControl {

        /** True while the engine still has a schematic loaded. */
        boolean isRunning();

        /** True when the engine has paused itself (e.g. it ran out of blocks). */
        boolean isPaused();

        /** Tells the engine to carry on from where it stopped. */
        void resume();
    }

    /** Supplies outstanding material; returns how many items actually arrived. */
    public interface Supply {

        /**
         * Tries to obtain the still-missing blocks.
         *
         * @return the number of items that genuinely reached the inventory; {@code 0}
         *         when nothing could be supplied
         */
        int supplyOutstanding();
    }

    /** Verifies the world against the schematic. */
    public interface Progress {

        /**
         * Counts the schematic blocks that are not yet correct in the world.
         *
         * @return the outstanding block count; {@code 0} means the build is done
         */
        int outstandingBlocks();
    }

    private final BuildControl control;
    private final Supply supply;
    private final Progress progress;

    private boolean active;
    private int attempts;
    private Status status = Status.IDLE;

    public BuildSupervisor(BuildControl control, Supply supply, Progress progress) {
        this.control = control;
        this.supply = supply;
        this.progress = progress;
    }

    /** Starts supervising a build that has just been handed to the engine. */
    public void begin() {
        this.active = true;
        this.attempts = 0;
        this.status = Status.RUNNING;
    }

    /** Stops supervising; used on cancel and when the build is abandoned. */
    public void end() {
        this.active = false;
        this.attempts = 0;
        this.status = Status.IDLE;
    }

    public boolean isActive() {
        return this.active;
    }

    public Status status() {
        return this.status;
    }

    /** Attempts used since the last successful supply round. */
    public int attempts() {
        return this.attempts;
    }

    /**
     * Advances the build by one step.
     *
     * @return {@code true} when something changed (completion, a resume, or a new
     *         pause) and the caller should report it
     */
    public boolean tick() {
        if (!this.active) {
            return false;
        }

        // The engine cleared its schematic: it believes it is finished. Verify
        // against the world rather than trusting that, because a build that
        // stalled earlier can leave blocks genuinely unplaced.
        if (!this.control.isRunning()) {
            return finish();
        }

        if (!this.control.isPaused()) {
            // Progress is being made. Reset the retry budget so a single bad
            // moment cannot end an otherwise healthy build.
            this.attempts = 0;
            this.status = Status.RUNNING;
            return false;
        }

        return recoverFromPause();
    }

    /**
     * The engine paused. Try to supply what is missing and resume it.
     *
     * <p>Bounded on purpose: if supply cannot make progress the engine is left
     * paused and the build is reported, instead of spinning forever.
     */
    private boolean recoverFromPause() {
        if (this.attempts >= MAX_SUPPLY_ATTEMPTS) {
            this.status = Status.PAUSED;
            this.active = false;
            return true;
        }

        this.attempts++;

        int supplied = 0;

        try {
            supplied = this.supply.supplyOutstanding();
        } catch (Throwable t) {
            // A supplier that throws is a supplier that supplied nothing.
            supplied = 0;
        }

        if (supplied <= 0) {
            // Still nothing in hand. Let the engine keep its paused state and
            // try again next tick, until the retry budget runs out.
            return false;
        }

        // Blocks really arrived, so the engine can carry on from where it
        // stopped rather than starting again.
        this.control.resume();
        this.attempts = 0;
        this.status = Status.RUNNING;
        return true;
    }

    /** Verifies the finished build against the world and settles the outcome. */
    private boolean finish() {
        this.active = false;
        this.attempts = 0;

        int outstanding = 0;

        try {
            outstanding = this.progress.outstandingBlocks();
        } catch (Throwable t) {
            // If verification cannot run we must not claim success.
            outstanding = -1;
        }

        if (outstanding == 0) {
            this.status = Status.COMPLETE;
            return true;
        }

        this.status = Status.PAUSED;
        return true;
    }
}
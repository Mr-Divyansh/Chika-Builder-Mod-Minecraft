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
 *       chain for more. The engine is resumed <b>only</b> when the round
 *       delivered blocks <i>and</i> the live inventory confirms that every
 *       remaining requirement is satisfied - a partial hand-over (say 191 of
 *       248) leaves the build paused and triggers another supply round.</li>
 *   <li>If supplying cannot make progress, give up after a bounded number of
 *       no-progress attempts and report {@code PAUSED} with the exact shortfall.</li>
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

    /**
     * Outcome of one supply round, from the live inventory.
     *
     * @param delivered how many items genuinely reached the player's storage
     *                  (re-counted, never claimed)
     * @param satisfied {@code true} only when a re-plan against the live
     *                  inventory shows <b>every</b> material requirement of the
     *                  build is covered - across all material types, not just
     *                  the one that was just handed over
     */
    public record SupplyResult(int delivered, boolean satisfied) {

        /** Nothing arrived and something is still missing. */
        public static SupplyResult none() {
            return new SupplyResult(0, false);
        }
    }

    /**
     * Supplies outstanding material; reports what arrived and whether the
     * requirements are now actually satisfied.
     */
    public interface Supply {

        /**
         * Tries to obtain the still-missing blocks and re-counts the inventory.
         *
         * @return {@link SupplyResult} with the number of items that genuinely
         *         reached the inventory and whether <b>all</b> requirements are
         *         satisfied; never {@code null}
         */
        SupplyResult supplyOutstanding();
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
    private boolean supplyInFlight;
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
        this.supplyInFlight = false;
        this.status = Status.RUNNING;
    }

    /** Stops supervising; used on cancel and when the build is abandoned. */
    public void end() {
        this.active = false;
        this.attempts = 0;
        this.supplyInFlight = false;
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
     * True while a supply round is in flight.
     *
     * <p>The movement watchdog uses this so it never "recovers" a build that is
     * merely waiting for materials: fetching blocks legitimately looks like the
     * player standing still.
     */
    public boolean isSupplyPending() {
        return this.active && this.supplyInFlight;
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
     * The engine paused. Supply what is missing, and resume it <b>only</b> when
     * the live inventory confirms the requirements are actually satisfied.
     *
     * <p>The resume condition is deliberately strict, because a live test once
     * showed the failure mode: Creative delivered 191 of 248 blocks and the
     * build was resumed anyway, running on with 57 required blocks missing.
     * "Some blocks arrived" is not a reason to resume; "nothing is short any
     * more" is.
     *
     * <p>Bounded on purpose: rounds that deliver nothing count against
     * {@link #MAX_SUPPLY_ATTEMPTS}; rounds that make real progress reset that
     * budget but still never resume the engine while a shortfall remains. So a
     * build either completes its supply, or ends paused with the exact
     * remainder - it can never spin forever and never resumes half-supplied.
     */
    private boolean recoverFromPause() {
        if (this.attempts >= MAX_SUPPLY_ATTEMPTS) {
            this.status = Status.PAUSED;
            this.active = false;
            return true;
        }

        this.attempts++;

        SupplyResult result = SupplyResult.none();

        try {
            // Flagged across the call so the movement watchdog can tell a
            // build that is waiting on materials from one that is stalled.
            this.supplyInFlight = true;
            SupplyResult supplied = this.supply.supplyOutstanding();
            if (supplied != null) {
                result = supplied;
            }
        } catch (Throwable t) {
            // A supplier that throws is a supplier that supplied nothing.
            result = SupplyResult.none();
        } finally {
            this.supplyInFlight = false;
        }

        if (result.satisfied() && result.delivered() > 0) {
            // Blocks really arrived AND the re-plan against the live inventory
            // shows every requirement covered: the only legal resume.
            this.control.resume();
            this.attempts = 0;
            this.status = Status.RUNNING;
            return true;
        }

        if (result.delivered() > 0) {
            // Partial hand-over: progress was made but at least one material is
            // still short. The engine STAYS PAUSED (the premature-resume bug).
            // Real progress resets only the no-progress budget, so a build that
            // is still closing the gap is not abandoned, while a supply that
            // stops delivering still ends in a bounded PAUSED report.
            this.attempts = 0;
        }

        return false;
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
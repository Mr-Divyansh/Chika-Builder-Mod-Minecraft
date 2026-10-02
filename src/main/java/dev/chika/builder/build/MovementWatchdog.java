package dev.chika.builder.build;

import dev.chika.builder.build.material.PlayerPosition;

/**
 * Detects a build that has stopped moving, and recovers it through the engine's
 * own supported API.
 *
 * <p><b>Why this exists.</b> A live test showed the player standing on a placed
 * block, not moving to the next required position, while the schematic was
 * still unfinished. Reading the engine's bytecode explains it precisely:
 * <ul>
 *   <li>{@code BuilderProcess.onTick} pauses <i>only</i> when it cannot compute
 *       a goal at all ("Unable to do it. Pausing."). It has <b>no no-progress
 *       counter</b>: if a goal exists but the path to it is stale or
 *       unreachable, it re-returns that same goal every tick and never
 *       pauses.</li>
 *   <li>{@code isPaused()} therefore stays {@code false} forever while the
 *       player stands still.</li>
 *   <li>{@link BuildSupervisor} treated "not paused" as "making progress", so
 *       the stall was invisible to it. That is the actual defect: a movement
 *       stall produced no state change anywhere in the build loop.</li>
 * </ul>
 *
 * <p><b>What recovery is safe here.</b> The engine exposes
 * {@code IPathingBehavior.cancelEverything()} and {@code forceCancel()}, but
 * both route through the engine's internal {@code PathingControlManager}, which
 * calls {@code onLostControl()} on every process. On the builder that nulls its
 * schematic field, and {@code isActive()} is implemented as
 * {@code schematic != null} - so those calls would silently discard the build
 * and could fake completion. Neither is used.
 *
 * <p>Recovery instead uses only {@code IBuilderProcess.pause()} followed, one
 * tick later, by {@code resume()}. Those two flip a single boolean and nothing
 * else. Verified in the engine's own bytecode: {@code BuilderProcess.onTick}
 * checks the paused flag and, while it is set, returns
 * {@code PathingCommandType.CANCEL_AND_SET_GOAL} - which cancels the current
 * path. Once resumed, the next tick recomputes the goal and re-plans from
 * scratch. The pause and the resume are deliberately on <b>different</b> ticks:
 * a synchronous pause-then-resume would never let the engine observe the paused
 * state, so the stale path would never actually be cancelled.
 *
 * <p>That is a genuine repath through the engine's public API: the player is
 * never teleported, never nudged, and collision and pathfinding are untouched.
 *
 * <p><b>Conservatism.</b> Standing still is normal: placing several blocks from
 * one spot, calculating a path, loading chunks. So the watchdog requires
 * <i>all</i> of the following, for {@link #DEFAULT_STATIONARY_TICK_LIMIT}
 * consecutive ticks, before it acts:
 * <ol>
 *   <li>the build is running and the schematic still has blocks remaining,</li>
 *   <li>the engine is <b>not</b> paused - a material pause is the supervisor's
 *       job and is never touched here,</li>
 *   <li>no material acquisition is pending,</li>
 *   <li>the engine is not actively pathing,</li>
 *   <li>the player's block position has not changed,</li>
 *   <li>the remaining-block count has not changed - so no placement happened.</li>
 * </ol>
 * Any movement, placement, pause, pathing, or completion resets the counter.
 *
 * <p>Recovery is bounded by {@link #MAX_RECOVERIES} with a cooldown between
 * attempts, so a genuinely stuck build reports the limitation instead of
 * looping forever. All engine contact goes through {@link Engine}, so every
 * branch here is unit testable without a running game.
 */
public final class MovementWatchdog {

    /**
     * Consecutive qualifying ticks of "no movement and no progress" before a
     * recovery is attempted.
     *
     * <p>At 20 ticks/second this is 10 seconds - comfortably longer than
     * placing a run of blocks from one position, calculating a path, or a chunk
     * load, all of which legitimately look stationary from outside.
     */
    public static final int DEFAULT_STATIONARY_TICK_LIMIT = 200;

    /** Ticks to wait after a recovery before another one may be attempted. */
    public static final int DEFAULT_COOLDOWN_TICKS = 100;

    /** How many recoveries one build may receive before giving up. */
    public static final int MAX_RECOVERIES = 3;

    /**
     * Ticks allowed for a recovery to show a result before it is called failed.
     *
     * <p>The previous version returned {@code RECOVERED} the instant the engine
     * accepted the pause, which is why a live log claimed "progress resumed
     * after repath" three times while {@code remaining} stayed at 52 and the
     * goal stayed {@code JankyGoalComposite}. A recovery is only reported as
     * successful once the goal, the path destination or the world has actually
     * changed; otherwise it counts against the budget as a failure.
     */
    public static final int DEFAULT_VERIFY_TICKS = 60;

    /**
     * The engine, reduced to exactly what the watchdog needs.
     *
     * <p>Deliberately narrow. There is no "move the player" or "teleport" method
     * here and none may be added: recovery is only ever a re-plan request that
     * the engine performs itself.
     */
    public interface Engine {

        /** True while the engine still has a schematic loaded. */
        boolean isRunning();

        /** True when the engine has paused itself, e.g. for missing material. */
        boolean isPaused();

        /** True when a path or path calculation is currently in progress. */
        boolean isPathing();

        /** The engine's current goal, for diagnostics. Never null. */
        String goal();

        /**
         * A stable identity for the current goal that changes when the engine
         * picks a <b>different</b> target.
         *
         * <p>{@link #goal()} returns the goal's class name, which stayed
         * {@code JankyGoalComposite} across every recovery in the live log even
         * though the build was frozen. This returns the goal's {@code toString()}
         * - for the engine's own goal types that embeds the target position, so
         * it genuinely differs when a new target is chosen. It is what lets the
         * watchdog tell "a new target was calculated" from "the same target was
         * handed back".
         */
        String goalIdentity();

        /**
         * Where the engine's current path is heading, or a sentinel when it has
         * no path. Compared between ticks as a further progress signal.
         */
        String pathDestination();

        /**
         * Phase 1 of a re-plan: ask the engine to stop acting on its current
         * path.
         *
         * <p>On the engine's next tick this makes it observe the paused state
         * and return {@code CANCEL_AND_SET_GOAL}, which cancels the stale path.
         *
         * @return true if the pause was accepted
         */
        boolean beginRepath();

        /**
         * Phase 2 of a re-plan: let the engine act again.
         *
         * <p>Called on a later tick than {@link #beginRepath()}, so the engine
         * really does cancel the old path before it re-plans. The schematic is
         * untouched and already-placed blocks are kept.
         */
        void finishRepath();
    }

    /** Read-only world state the watchdog needs in order to judge progress. */
    public interface World {

        /** The player's current block position, or null when unavailable. */
        PlayerPosition playerPosition();

        /** Schematic blocks not yet correct in the world; 0 means finished. */
        int remainingBlocks();
    }

    /** Reports whether material work is in flight. */
    public interface Activity {

        /** True while a Creative/Shop supply round is pending. */
        boolean isSupplyPending();
    }

    /** Sink for watchdog logging, so the watchdog never logs directly. */
    public interface Reporter {

        void report(String message);
    }

    /** What the watchdog did this tick. */
    public enum Outcome {
        /** Nothing to do - the build is healthy or legitimately stationary. */
        NONE,
        /**
         * A stall was confirmed and a re-plan was requested.
         *
         * <p>Deliberately <b>not</b> called {@code RECOVERED}: nothing is known
         * yet about whether it worked. The result is decided by
         * {@link #DEFAULT_VERIFY_TICKS} of watching.
         */
        REPATH_STARTED,
        /** A re-plan was verified: the engine moved, placed, or re-targeted. */
        RECOVERED,
        /** A stall was confirmed but recovery is impossible or exhausted. */
        LIMITATION
    }

    private final Engine engine;
    private final World world;
    private final Activity activity;
    private final Reporter reporter;
    private final int tickLimit;
    private final int cooldownTicks;

    private boolean watching;
    private PlayerPosition lastPosition;
    private int lastRemaining = -1;
    private int stationaryTicks;
    private int cooldown;
    private int recoveries;
    private int lastMovementTick;
    private int lastProgressTick;
    private int tickCount;
    private boolean repathPending;

    /** Last observed goal identity; part of the progress signal set. */
    private String lastGoalIdentity = "unavailable";

    /** Last observed path destination; part of the progress signal set. */
    private String lastDestination = "none";

    /**
     * The goal identity at the moment the current recovery was requested.
     *
     * <p>Compared against the live goal so a recovery is only called successful
     * when the engine actually picked a different target.
     */
    private String goalAtRecovery = "unavailable";

    /** The destination at the moment the current recovery was requested. */
    private String destinationAtRecovery = "none";

    /** True while a requested recovery is being given time to show a result. */
    private boolean verifying;

    /** Ticks spent verifying the current recovery. */
    private int verifyTicks;

    /**
     * Highest stationary-tick milestone already logged (0, 1, 2 or 3).
     *
     * <p>Reset whenever any progress signal fires, so the 5s and 10s notes are
     * logged once per genuine stall rather than once per tick.
     */
    private int stallMilestone;

    private final int verifyTicksLimit;

    /**
     * Which diagnostic milestone {@code stationaryTicks} has reached.
     *
     * @return 1 at the first stationary tick, 2 at five seconds (100 ticks),
     *         3 at ten seconds (200 ticks), otherwise 0
     */
    private int milestoneFor(int stationaryTicks) {
        if (stationaryTicks >= 200) {
            return 3;
        }
        if (stationaryTicks >= 100) {
            return 2;
        }
        if (stationaryTicks >= 1) {
            return 1;
        }
        return 0;
    }

    /**
     * The full watchdog picture on one line, for the log.
     *
     * <p>This is the line that makes a live stall diagnosable: it names the
     * player position, what is still outstanding, the engine's goal class, the
     * goal identity (which embeds the target position), the path destination and
     * whether the engine considers itself busy.
     */
    private String describeStall(PlayerPosition position, int remaining,
                                String goalIdentity, String destination) {
        return "status=watching"
                + " player=" + (position == null ? "unavailable" : position)
                + " remaining=" + remaining
                + " goal=" + safeGoal()
                + " goalIdentity=" + goalIdentity
                + " pathDestination=" + destination
                + " pathing=" + this.engine.isPathing()
                + " paused=" + this.engine.isPaused()
                + " active=" + this.engine.isRunning()
                + " stationaryTicks=" + this.stationaryTicks
                + " recoveries=" + this.recoveries + "/" + MAX_RECOVERIES
                + " lastProgressTick=" + this.lastProgressTick;
    }

    public MovementWatchdog(Engine engine, World world, Activity activity, Reporter reporter) {
        this(engine, world, activity, reporter, DEFAULT_STATIONARY_TICK_LIMIT, DEFAULT_COOLDOWN_TICKS,
                DEFAULT_VERIFY_TICKS);
    }

    public MovementWatchdog(Engine engine, World world, Activity activity, Reporter reporter,
                            int tickLimit, int cooldownTicks) {
        this(engine, world, activity, reporter, tickLimit, cooldownTicks, DEFAULT_VERIFY_TICKS);
    }

    public MovementWatchdog(Engine engine, World world, Activity activity, Reporter reporter,
                            int tickLimit, int cooldownTicks, int verifyTicks) {
        this.engine = engine;
        this.world = world;
        this.activity = activity;
        this.reporter = reporter;
        this.tickLimit = tickLimit;
        this.cooldownTicks = cooldownTicks;
        this.verifyTicksLimit = verifyTicks;
    }

    /** Starts watching a fresh build; resets every counter. */
    public void begin() {
        this.watching = true;
        this.lastPosition = null;
        this.lastRemaining = -1;
        this.stationaryTicks = 0;
        this.cooldown = 0;
        this.recoveries = 0;
        this.lastMovementTick = 0;
        this.lastProgressTick = 0;
        this.tickCount = 0;
        this.lastGoalIdentity = safeGoalIdentity();
        this.lastDestination = safeDestination();
        this.goalAtRecovery = this.lastGoalIdentity;
        this.destinationAtRecovery = this.lastDestination;
        this.verifying = false;
        this.verifyTicks = 0;
        this.repathPending = false;
    }

    /** Stops watching; used when the build ends or is abandoned. */
    public void end() {
        this.watching = false;
        this.stationaryTicks = 0;
        this.cooldown = 0;
        this.verifying = false;
        this.verifyTicks = 0;
    }

    /** True while a re-plan is half-done (paused, awaiting its resume tick). */
    public boolean isRepathing() {
        return this.repathPending;
    }

    public boolean isWatching() {
        return this.watching;
    }

    /** Consecutive stationary, no-progress ticks observed so far. */
    public int stationaryTicks() {
        return this.stationaryTicks;
    }

    /** Recoveries performed for the current build. */
    public int recoveries() {
        return this.recoveries;
    }

    /** Tick of the last observed player movement. */
    public int lastMovementTick() {
        return this.lastMovementTick;
    }

    /** Tick of the last observed placement/progress change. */
    public int lastProgressTick() {
        return this.lastProgressTick;
    }

    /**
     * Advances the watchdog by one client tick.
     *
     * @return what was done, so the caller can report a real recovery
     */
    public Outcome tick() {
        if (!this.watching) {
            return Outcome.NONE;
        }

        this.tickCount++;

        // Phase 2 of a re-plan: let the engine act again, now that it has had a
        // tick to cancel the stale path. This is deliberately a later tick than
        // the pause, because the engine only cancels the path when its own tick
        // observes the paused flag.
        //
        // Verification deliberately continues on this tick rather than
        // returning: the engine has only just been released, so the very first
        // opportunity to pick a new target is now. Returning here would hide
        // the re-target from the check and could report a healthy recovery as a
        // failure.
        if (this.repathPending) {
            this.repathPending = false;
            this.engine.finishRepath();
            this.report("re-plan released - the engine may act again and will re-derive its "
                    + "target from the player's current position (baseline was "
                    + this.goalAtRecovery + ")");
        }

        // A finished build is never a stall. This also covers the engine
        // clearing its own schematic when a build completes.
        if (!this.engine.isRunning()) {
            this.end();
            return Outcome.NONE;
        }

        int remaining = safeRemaining();
        PlayerPosition position = safePosition();
        String goalIdentity = safeGoalIdentity();
        String destination = safeDestination();

        // --- Progress: movement resets the stationary counter. ---
        boolean moved = position != null && !position.equals(this.lastPosition);

        if (position != null) {
            this.lastPosition = position;
        }

        if (moved) {
            this.lastMovementTick = this.tickCount;
        }

        // --- Progress: a placement changes the remaining count. ---
        boolean placed = remaining >= 0 && remaining != this.lastRemaining;

        if (placed) {
            this.lastProgressTick = this.tickCount;
            this.lastRemaining = remaining;
        }

        // --- Progress: a new goal identity or path destination. ---
        // Both are genuine forward movement while the player stands still,
        // which is exactly what the old single-signal check could not see.
        boolean newGoal = !goalIdentity.equals(this.lastGoalIdentity);
        boolean newPath = !destination.equals(this.lastDestination);

        this.lastGoalIdentity = goalIdentity;
        this.lastDestination = destination;

        if (moved || placed || newGoal || newPath) {
            this.stationaryTicks = 0;
            this.stallMilestone = 0;
        } else if (position != null && remaining >= 0) {
            // Only count a stall when both the player position and the remaining
            // count are readable. An unreadable reading is not evidence of a
            // stall and must never be treated as one.
            this.stationaryTicks++;

            // Logged at the moments a stall investigator actually needs, and
            // never per tick: stationary detection starting, then 5s and 10s.
            int milestone = milestoneFor(this.stationaryTicks);

            if (milestone > this.stallMilestone) {
                this.stallMilestone = milestone;
                this.report("stationary " + describeStall(position, remaining, goalIdentity,
                        destination) + " (still watching, no action taken)");
            }
        }

        // --- Verifying a recovery that is already in flight. ---
        if (this.verifying) {
            return verifyRecovery(moved, placed, newGoal, newPath, goalIdentity, destination);
        }

        // --- Reasons this is legitimately not a stall. ---
        if (this.cooldown > 0) {
            this.cooldown--;
            return Outcome.NONE;
        }

        if (this.activity.isSupplyPending()) {
            // Materials are being fetched; standing still is expected.
            this.stationaryTicks = 0;
            return Outcome.NONE;
        }

        if (this.engine.isPaused()) {
            // A material pause is BuildSupervisor's responsibility. The
            // watchdog must not resume, repath or otherwise interfere.
            this.stationaryTicks = 0;
            return Outcome.NONE;
        }

        if (this.engine.isPathing()) {
            // Actively pathing or calculating: a long computation looks
            // stationary from out here but is real work in progress.
            this.stationaryTicks = 0;
            return Outcome.NONE;
        }

        if (remaining == 0) {
            // Schematic satisfied - nothing left to move towards.
            this.stationaryTicks = 0;
            return Outcome.NONE;
        }

        if (this.stationaryTicks < this.tickLimit) {
            return Outcome.NONE;
        }

        return recover(remaining, position, goalIdentity, destination);
    }

    /**
     * Decides whether the recovery just requested actually worked.
     *
     * <p>This is the check the previous version never made: it reported success
     * the moment the engine accepted the pause, so a frozen build logged
     * "progress resumed after repath" three times while nothing had changed.
     *
     * <p>Success means the world moved, a block was placed, or the engine chose
     * a genuinely different goal or path. A new goal is accepted as progress
     * because a re-plan that converges on the same target is exactly what a
     * healthy build looks like.
     *
     * @return {@link Outcome#RECOVERED} once real progress is seen, or
     *         {@link Outcome#LIMITATION} once the budget is spent
     */
    private Outcome verifyRecovery(boolean moved, boolean placed, boolean newGoal,
                                   boolean newPath, String goalIdentity, String destination) {

        if (moved || placed) {
            // Unambiguous: the world changed. This is what "recovered" means.
            this.verifying = false;
            this.verifyTicks = 0;
            this.cooldown = this.cooldownTicks;
            this.report("recovered: " + (moved ? "the player moved" : "a block was placed")
                    + " after the re-plan");
            return Outcome.RECOVERED;
        }

        if (newGoal || newPath) {
            this.verifying = false;
            this.verifyTicks = 0;
            this.cooldown = this.cooldownTicks;
            this.report("recovered: the engine chose a new target (goal=" + goalIdentity
                    + " destination=" + destination + ")");
            return Outcome.RECOVERED;
        }

        this.verifyTicks++;

        if (this.verifyTicks < this.verifyTicksLimit) {
            // Still within the engine's grace period; do not judge it yet.
            return Outcome.NONE;
        }

        // The engine handed back exactly the target it already had.
        this.verifying = false;
        this.verifyTicks = 0;
        this.stationaryTicks = 0;
        this.cooldown = this.cooldownTicks;

        this.report("recovery attempt produced no change: the engine returned the same target ("
                + goalIdentity + ") and neither placed nor moved anything");

        if (this.recoveries >= MAX_RECOVERIES) {
            this.report("recovery limit reached (" + this.recoveries + " attempts) - the engine "
                    + "keeps re-deriving the same target, so it is not holding a stale path");
            return Outcome.LIMITATION;
        }

        // A fresh full window must elapse before the next attempt.
        return Outcome.NONE;
    }

    /**
     * A genuine stall: every precondition held for {@link #tickLimit} ticks.
     *
     * <p>Re-checks the live state before acting, so a stall is only recovered
     * from while it is still a stall, never on stale readings.
     */
    private Outcome recover(int remaining, PlayerPosition position, String goalIdentity,
                            String destination) {
        this.stationaryTicks = 0;

        if (!this.engine.isRunning() || this.engine.isPaused() || this.activity.isSupplyPending()) {
            return Outcome.NONE;
        }

        int recheck = safeRemaining();
        if (recheck == 0) {
            return Outcome.NONE;
        }

        // The full stall picture, on one line, at the moment it is acted on.
        this.report("BUILD STALLED  remaining=" + recheck
                + " player=" + (position == null ? "unavailable" : position)
                + " goal=" + safeGoal()
                + " goalIdentity=" + goalIdentity
                + " pathDestination=" + destination
                + " pathing=" + this.engine.isPathing()
                + " paused=" + this.engine.isPaused()
                + " active=" + this.engine.isRunning()
                + " stationaryTicks=" + this.tickLimit
                + " recoveries=" + this.recoveries + "/" + MAX_RECOVERIES
                + " lastMovementTick=" + this.lastMovementTick
                + " lastProgressTick=" + this.lastProgressTick);

        if (this.recoveries >= MAX_RECOVERIES) {
            this.report("RECOVERY LIMIT REACHED after " + this.recoveries
                    + " attempt(s); the engine kept re-deriving the same target, so it is"
                    + " not holding a stale path. Pausing the build.");
            return Outcome.LIMITATION;
        }

        // Snapshot what the engine is holding now, so the result can be judged
        // against it rather than assumed.
        this.goalAtRecovery = goalIdentity;
        this.destinationAtRecovery = destination;
        this.verifying = true;
        this.verifyTicks = 0;

        this.recoveries++;
        this.cooldown = this.cooldownTicks;

        boolean started;
        try {
            started = this.engine.beginRepath();
        } catch (Throwable t) {
            this.verifying = false;
            this.report("repath request failed: " + t);
            return Outcome.LIMITATION;
        }

        if (!started) {
            this.verifying = false;
            this.report("the engine declined the re-plan - reporting the limitation"
                    + " rather than moving the player");
            return Outcome.LIMITATION;
        }

        // Resumed on the next tick, once the engine has cancelled the stale path.
        this.repathPending = true;
        this.report("attempting re-plan (recovery " + this.recoveries + " of " + MAX_RECOVERIES
                + ") from goal=" + goalIdentity + "; waiting up to " + this.verifyTicksLimit
                + " ticks to see whether it actually changes anything");
        return Outcome.REPATH_STARTED;
    }

    /** A one-line status summary for the log and {@code #chika_builder debug}. */
    public String describe() {
        return "active=" + this.engine.isRunning()
                + " paused=" + this.engine.isPaused()
                + " pathing=" + this.engine.isPathing()
                + " stationaryTicks=" + this.stationaryTicks + "/" + this.tickLimit
                + " recoveries=" + this.recoveries + "/" + MAX_RECOVERIES
                + " remaining=" + safeRemaining()
                + " goal=" + safeGoal()
                + " goalIdentity=" + this.lastGoalIdentity
                + " pathDestination=" + this.lastDestination
                + " verifying=" + this.verifying
                + " verifyTicks=" + this.verifyTicks + "/" + this.verifyTicksLimit;
    }

    private int safeRemaining() {
        try {
            return this.world.remainingBlocks();
        } catch (Throwable t) {
            return -1;
        }
    }

    private PlayerPosition safePosition() {
        try {
            return this.world.playerPosition();
        } catch (Throwable t) {
            return null;
        }
    }

    private String safeGoal() {
        try {
            String goal = this.engine.goal();
            return goal == null ? "unavailable" : goal;
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /**
     * The engine's current goal identity, defensively read.
     *
     * <p>Falls back to {@link #safeGoal()} so a backend that cannot supply a
     * richer identity still reports something, and never returns null.
     */
    private String safeGoalIdentity() {
        try {
            String identity = this.engine.goalIdentity();
            return identity == null ? safeGoal() : identity;
        } catch (Throwable t) {
            return safeGoal();
        }
    }

    /** The engine's current path destination, defensively read. */
    private String safeDestination() {
        try {
            String destination = this.engine.pathDestination();
            return destination == null ? "none" : destination;
        } catch (Throwable t) {
            return "none";
        }
    }

    private void report(String message) {
        try {
            this.reporter.report(message);
        } catch (Throwable ignored) {
            // Diagnostics must never break the build loop.
        }
    }
}

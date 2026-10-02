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
        /** A stall was confirmed and a repath was requested. */
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

    public MovementWatchdog(Engine engine, World world, Activity activity, Reporter reporter) {
        this(engine, world, activity, reporter, DEFAULT_STATIONARY_TICK_LIMIT, DEFAULT_COOLDOWN_TICKS);
    }

    public MovementWatchdog(Engine engine, World world, Activity activity, Reporter reporter,
                            int tickLimit, int cooldownTicks) {
        this.engine = engine;
        this.world = world;
        this.activity = activity;
        this.reporter = reporter;
        this.tickLimit = tickLimit;
        this.cooldownTicks = cooldownTicks;
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
    }

    /** Stops watching; used when the build ends or is abandoned. */
    public void end() {
        this.watching = false;
        this.stationaryTicks = 0;
        this.cooldown = 0;
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
        if (this.repathPending) {
            this.repathPending = false;
            this.engine.finishRepath();
            this.report("repath complete - engine re-planning from its current goal");
            return Outcome.NONE;
        }

        // A finished build is never a stall. This also covers the engine
        // clearing its own schematic when a build completes.
        if (!this.engine.isRunning()) {
            this.end();
            return Outcome.NONE;
        }

        int remaining = safeRemaining();
        PlayerPosition position = safePosition();

        // --- Progress: movement resets the stationary counter. ---
        if (position != null && !position.equals(this.lastPosition)) {
            this.lastMovementTick = this.tickCount;
            this.lastPosition = position;
            this.stationaryTicks = 0;
        } else {
            if (position != null) {
                this.lastPosition = position;
            }
            this.stationaryTicks++;
        }

        // --- Progress: a placement changes the remaining count. ---
        if (remaining >= 0 && remaining != this.lastRemaining) {
            this.lastProgressTick = this.tickCount;
            this.lastRemaining = remaining;
            this.stationaryTicks = 0;
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

        return recover(remaining);
    }

    /**
     * A genuine stall: every precondition held for {@link #tickLimit} ticks.
     *
     * <p>Re-checks the live state before acting, so a stall is only recovered
     * from while it is still a stall, never on stale readings.
     */
    private Outcome recover(int remaining) {
        this.stationaryTicks = 0;

        if (!this.engine.isRunning() || this.engine.isPaused() || this.activity.isSupplyPending()) {
            return Outcome.NONE;
        }

        int recheck = safeRemaining();
        if (recheck == 0) {
            return Outcome.NONE;
        }

        this.report("active=" + this.engine.isRunning()
                + " paused=" + this.engine.isPaused()
                + " remaining=" + recheck
                + " playerStationaryTicks=" + this.tickLimit
                + " lastMovementTick=" + this.lastMovementTick
                + " lastProgressTick=" + this.lastProgressTick);
        this.report("goal=" + safeGoal());

        if (this.recoveries >= MAX_RECOVERIES) {
            this.report("recovery limit reached (" + this.recoveries
                    + " attempts) - the build is not advancing and the engine"
                    + " exposes no further safe recovery");
            return Outcome.LIMITATION;
        }

        this.report("attempting safe repath");
        this.recoveries++;
        this.cooldown = this.cooldownTicks;

        boolean started;
        try {
            started = this.engine.beginRepath();
        } catch (Throwable t) {
            this.report("repath request failed: " + t);
            return Outcome.LIMITATION;
        }

        if (!started) {
            this.report("the engine declined the repath - reporting the limitation"
                    + " rather than moving the player");
            return Outcome.LIMITATION;
        }

        // Resumed on the next tick, once the engine has cancelled the stale path.
        this.repathPending = true;
        this.report("repath requested (recovery " + this.recoveries + " of " + MAX_RECOVERIES + ")");
        return Outcome.RECOVERED;
    }

    /** A one-line status summary for the log and {@code #chika_builder debug}. */
    public String describe() {
        return "active=" + this.engine.isRunning()
                + " paused=" + this.engine.isPaused()
                + " pathing=" + this.engine.isPathing()
                + " stationaryTicks=" + this.stationaryTicks + "/" + this.tickLimit
                + " recoveries=" + this.recoveries + "/" + MAX_RECOVERIES
                + " remaining=" + safeRemaining();
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

    private void report(String message) {
        try {
            this.reporter.report(message);
        } catch (Throwable ignored) {
            // Diagnostics must never break the build loop.
        }
    }
}

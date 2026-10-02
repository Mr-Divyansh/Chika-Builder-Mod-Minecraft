package dev.chika.builder.build;

import dev.chika.builder.build.material.PlayerPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the movement stall watchdog.
 *
 * <p>These cover the exact live failure: the player stands on one placed block,
 * never moves to the next required position, and the build sits there forever
 * with blocks still outstanding. The engine never pauses in that state, so the
 * material supervisor had nothing to react to.
 *
 * <p>They also pin the safety rules that matter more than the recovery itself:
 * no teleport, no random movement, no resuming a material-paused build, no fake
 * completion, and no unbounded retry loop.
 */
class MovementWatchdogTest {

    /**
     * A short threshold keeps the tests fast while still exercising exactly the
     * same code path as the real 200-tick default.
     */
    private static final int LIMIT = 10;
    private static final int COOLDOWN = 3;

    /**
     * How long a recovery is given to prove itself.
     *
     * <p>Short here for speed; the production default is
     * {@link MovementWatchdog#DEFAULT_VERIFY_TICKS}.
     */
    private static final int VERIFY = 5;

    /** The watchdog under test, so the new tests can drive it directly. */
    private MovementWatchdog watchdog;

    /** Engine double that records exactly what the watchdog asked of it. */
    private static final class FakeEngine implements MovementWatchdog.Engine {

        boolean running = true;
        boolean paused;
        boolean pausedForRepath;
        boolean pathing;
        boolean repathAvailable = true;
        String goal = "GoalBlock";

        /**
         * Goal identity, deliberately separate from {@link #goal}.
         *
         * <p>Modelled that way because that is exactly the distinction the live
         * log exposed: the goal <i>class</i> stayed {@code JankyGoalComposite}
         * through every recovery while the build was frozen, so a class name is
         * not a usable progress signal on its own.
         */
        String goalIdentity = "GoalBlock@10,64,10";

        String destination = "none";
        int repaths;

        @Override
        public boolean isRunning() {
            return this.running;
        }

        @Override
        public boolean isPaused() {
            return this.paused;
        }

        @Override
        public boolean isPathing() {
            return this.pathing;
        }

        @Override
        public String goal() {
            return this.goal;
        }

        @Override
        public String goalIdentity() {
            return this.goalIdentity;
        }

        @Override
        public String pathDestination() {
            return this.destination;
        }

        @Override
        public boolean beginRepath() {
            // Mirrors the real contract: refuse when the build is not active.
            if (!this.running || !this.repathAvailable) {
                return false;
            }
            this.repaths++;
            // Phase 1: the engine is now asked to cancel its path. It stays
            // "paused" from the watchdog's point of view until the next tick.
            this.pausedForRepath = true;
            return true;
        }

        @Override
        public void finishRepath() {
            this.pausedForRepath = false;
        }
    }

    /** World double: the player never moves unless a test moves them. */
    private static final class FakeWorld implements MovementWatchdog.World {

        PlayerPosition position = new PlayerPosition(10, 64, 10);
        int remaining = 50;

        @Override
        public PlayerPosition playerPosition() {
            return this.position;
        }

        @Override
        public int remainingBlocks() {
            return this.remaining;
        }
    }

    private final FakeEngine engine = new FakeEngine();
    private final FakeWorld world = new FakeWorld();
    private final List<String> log = new ArrayList<>();

    private boolean supplyPending;

    private MovementWatchdog watchdog() {
        MovementWatchdog watchdog = new MovementWatchdog(
                this.engine, this.world, () -> this.supplyPending, this.log::add,
                LIMIT, COOLDOWN, VERIFY);
        watchdog.begin();
        return watchdog;
    }

    /**
     * Drives a genuine stall until the watchdog reports a non-NONE outcome,
     * giving it enough ticks to get past both the stall window and the
     * post-recovery verification window.
     */
    private MovementWatchdog.Outcome stallUntilReported(int maxTicks) {
        MovementWatchdog.Outcome outcome = MovementWatchdog.Outcome.NONE;

        for (int i = 0; i < maxTicks; i++) {
            outcome = this.watchdog.tick();

            if (outcome == MovementWatchdog.Outcome.REPATH_STARTED) {
                continue;
            }
            if (outcome != MovementWatchdog.Outcome.NONE) {
                return outcome;
            }
        }
        return outcome;
    }

    // --- 1. Normal stationary building must NOT trigger recovery. ---

    @Test
    void normalStationaryBuildingDoesNotTriggerRecovery() {
        MovementWatchdog watchdog = watchdog();

        // The player stays put but the builder keeps placing: the remaining
        // count drops every few ticks, exactly like placing several blocks from
        // one position. This is normal and must never be treated as a stall.
        MovementWatchdog.Outcome outcome = MovementWatchdog.Outcome.NONE;
        for (int i = 0; i < LIMIT * 4; i++) {
            if (i % 3 == 0 && this.world.remaining > 1) {
                this.world.remaining--;
            }
            outcome = watchdog.tick();
            assertEquals(MovementWatchdog.Outcome.NONE, outcome,
                    "placing from one spot is not a stall (tick " + i + ")");
        }

        assertEquals(0, this.engine.repaths, "a building player must never be repathed");
    }

    @Test
    void aShortStationaryBurstIsNotAStall() {
        MovementWatchdog watchdog = watchdog();

        // Well under the threshold: a path calculation looks like this.
        for (int i = 0; i < LIMIT - 1; i++) {
            assertEquals(MovementWatchdog.Outcome.NONE, watchdog.tick());
        }

        assertEquals(0, this.engine.repaths, "a brief pause must not trigger recovery");
    }

    // --- 2. Player movement resets the watchdog. ---

    @Test
    void playerMovementResetsTheWatchdog() {
        MovementWatchdog watchdog = watchdog();

        for (int i = 0; i < LIMIT - 1; i++) {
            watchdog.tick();
        }
        assertTrue(watchdog.stationaryTicks() > 0, "precondition: counting up");

        this.world.position = new PlayerPosition(11, 64, 10);
        watchdog.tick();

        assertEquals(0, watchdog.stationaryTicks(), "movement must reset the counter");
        assertTrue(watchdog.lastMovementTick() > 0, "the movement tick must be recorded");
    }

    // --- 3 & 4. Placements / remaining-count changes reset the watchdog. ---

    @Test
    void successfulBlockPlacementResetsTheWatchdog() {
        MovementWatchdog watchdog = watchdog();

        for (int i = 0; i < LIMIT - 1; i++) {
            watchdog.tick();
        }
        assertTrue(watchdog.stationaryTicks() > 0, "precondition: counting up");

        this.world.remaining = 49;
        watchdog.tick();

        assertEquals(0, watchdog.stationaryTicks(), "a placement must reset the counter");
        assertTrue(watchdog.lastProgressTick() > 0, "the progress tick must be recorded");
    }

    @Test
    void aChangingRemainingBlockCountResetsTheWatchdog() {
        MovementWatchdog watchdog = watchdog();

        // Remaining count keeps changing and never repeats: no stall is possible.
        MovementWatchdog.Outcome outcome = MovementWatchdog.Outcome.NONE;
        for (int i = 0; i < LIMIT * 5; i++) {
            this.world.remaining = 50 - i;
            outcome = watchdog.tick();
        }

        assertEquals(MovementWatchdog.Outcome.NONE, outcome);
        assertEquals(0, this.engine.repaths);
    }

    // --- 5. A genuine prolonged stall triggers recovery. ---

    @Test
    void aGenuineProlongedStationaryNoProgressStateTriggersRecovery() {
        this.watchdog = watchdog();

        // The live failure exactly: running, not paused, blocks outstanding,
        // player frozen on one block, no placement, engine not pathing.
        //
        // The outcome on the stall tick is REPATH_STARTED, never RECOVERED:
        // nothing is proven at that point. This is the distinction the previous
        // version got wrong, and why a live log claimed three successful
        // recoveries on a build that never moved.
        MovementWatchdog.Outcome outcome = tickUntil(this.watchdog, LIMIT + 5);

        assertEquals(MovementWatchdog.Outcome.REPATH_STARTED, outcome,
                "a re-plan must be requested, but must not be reported as a success yet");
        assertEquals(1, this.engine.repaths, "recovery must ask the engine to re-plan once");
    }

    @Test
    void aRepathThatChangesNothingIsNeverReportedAsRecovered() {
        this.watchdog = watchdog();

        MovementWatchdog.Outcome outcome = stallUntilReported(LIMIT * 6);

        // The engine handed back the identical target and nothing was placed or
        // moved, so there is no success to report - only a failed attempt that
        // must consume the bounded budget.
        assertNotEquals(MovementWatchdog.Outcome.RECOVERED, outcome,
                "an unchanged goal must never be reported as a successful recovery");
        assertTrue(everythingLogged().contains("produced no change"),
                "the failed attempt must be recorded, not silently dropped");
    }

    @Test
    void aRepathIsVerifiedWhenTheEngineActuallyRetargets() {
        this.watchdog = watchdog();

        assertEquals(MovementWatchdog.Outcome.REPATH_STARTED,
                tickUntil(this.watchdog, LIMIT + 5), "precondition: a re-plan is requested");

        // The engine now picks a genuinely different target, exactly as it would
        // if it had re-derived its goal from the player's current position.
        this.engine.goalIdentity = "GoalBlock@12,65,10";
        this.engine.destination = "(12,65,10)";

        assertEquals(MovementWatchdog.Outcome.RECOVERED, this.watchdog.tick(),
                "a genuinely new goal must be recognised as a verified recovery");
        assertTrue(everythingLogged().contains("chose a new target"),
                "the new goal must be named in the log");
    }

    @Test
    void aRepathIsVerifiedWhenThePlayerActuallyMoves() {
        this.watchdog = watchdog();

        tickUntil(this.watchdog, LIMIT + 5);
        this.engine.pausedForRepath = false;

        this.world.position = new PlayerPosition(11, 64, 10);

        assertEquals(MovementWatchdog.Outcome.RECOVERED, this.watchdog.tick(),
                "movement after a re-plan is a verified recovery");
        assertTrue(everythingLogged().contains("the player moved"));
    }

    @Test
    void theReplanIsReleasedOnALaterTickNotSynchronously() {
        // The engine only cancels a stale path when its own tick *observes* the
        // paused flag. A synchronous pause+resume would never be observed, so
        // the re-plan must span two ticks.
        MovementWatchdog watchdog = watchdog();
        tickUntil(watchdog, LIMIT + 5);

        assertTrue(this.engine.pausedForRepath,
                "immediately after recovery the engine must still be holding the pause");
        assertTrue(watchdog.isRepathing(), "the re-plan must be pending its resume tick");

        watchdog.tick();

        assertFalse(this.engine.pausedForRepath,
                "the next tick must release the pause so the engine re-plans");
        assertFalse(watchdog.isRepathing());
    }

    @Test
    void recoveryIsLoggedWithTheDiagnosticDetail() {
        MovementWatchdog watchdog = watchdog();
        tickUntil(watchdog, LIMIT + 5);
        // One more tick, so the re-plan's release is logged too.
        watchdog.tick();

        String all = everythingLogged();
        assertTrue(all.contains("active=true"), all);
        assertTrue(all.contains("paused=false"), all);
        assertTrue(all.contains("remaining=50"), all);
        assertTrue(all.contains("goal=GoalBlock"), all);
        assertTrue(all.contains("BUILD STALLED"), all);
        assertTrue(all.contains("attempting re-plan"), all);
        assertTrue(all.contains("re-plan released"), all);
    }

    // --- 6 & 7. Recovery never moves the player. ---

    @Test
    void recoveryDoesNotTeleportThePlayer() {
        MovementWatchdog watchdog = watchdog();
        PlayerPosition before = this.world.position;

        tickUntil(watchdog, LIMIT + 5);

        assertEquals(before, this.world.position,
                "recovery must not move the player: it is a re-plan request only");
    }

    @Test
    void recoveryDoesNotRandomlyMoveThePlayer() {
        MovementWatchdog watchdog = watchdog();
        PlayerPosition before = this.world.position;

        // Several stall/recovery cycles, to prove nothing accumulates drift.
        for (int cycle = 0; cycle < 3; cycle++) {
            this.world.position = before;
            tickUntil(watchdog, LIMIT + COOLDOWN + 5);
        }

        assertEquals(before, this.world.position,
                "no recovery may nudge the player in any direction");
    }

    @Test
    void theWatchdogExposesNoWayToMoveThePlayer() {
        // Structural guarantee: the engine seam has no movement or teleport
        // operation at all, so no future edit can quietly add one.
        List<String> methods = new ArrayList<>();
        for (var method : MovementWatchdog.Engine.class.getDeclaredMethods()) {
            methods.add(method.getName().toLowerCase(java.util.Locale.ROOT));
        }

        for (String forbidden : List.of("teleport", "setposition", "moveplayer", "nudge", "jump")) {
            assertFalse(methods.contains(forbidden),
                    "the watchdog must expose no player-movement operation: " + forbidden);
        }
    }

    // --- 8. A material-paused build is never recovered from. ---

    @Test
    void recoveryDoesNotResumeAMaterialPausedBuildIncorrectly() {
        MovementWatchdog watchdog = watchdog();
        this.engine.paused = true;

        MovementWatchdog.Outcome outcome = tickUntil(watchdog, LIMIT * 4);

        assertEquals(MovementWatchdog.Outcome.NONE, outcome,
                "a paused build is the material supervisor's business");
        assertEquals(0, this.engine.repaths, "a paused build must never be repathed");
    }

    @Test
    void aPendingSupplyRoundIsNotAStall() {
        MovementWatchdog watchdog = watchdog();
        this.supplyPending = true;

        MovementWatchdog.Outcome outcome = tickUntil(watchdog, LIMIT * 4);

        assertEquals(MovementWatchdog.Outcome.NONE, outcome,
                "waiting for materials is legitimate stillness");
        assertEquals(0, this.engine.repaths);
    }

    // --- 9. A completed build never triggers the watchdog. ---

    @Test
    void aCompletedBuildNeverTriggersTheWatchdog() {
        MovementWatchdog watchdog = watchdog();
        this.world.remaining = 0;

        MovementWatchdog.Outcome outcome = tickUntil(watchdog, LIMIT * 4);

        assertEquals(MovementWatchdog.Outcome.NONE, outcome);
        assertEquals(0, this.engine.repaths, "a finished build must never be repathed");
    }

    @Test
    void aBuildThatEndsStopsTheWatchdogWatching() {
        MovementWatchdog watchdog = watchdog();
        this.engine.running = false;

        watchdog.tick();

        assertFalse(watchdog.isWatching(), "an ended build must stop being watched");
        assertEquals(0, this.engine.repaths);
    }

    /** Ticks until the watchdog reports something other than NONE. */
    private MovementWatchdog.Outcome tickUntil(MovementWatchdog watchdog, int maxTicks) {
        MovementWatchdog.Outcome outcome = MovementWatchdog.Outcome.NONE;
        for (int i = 0; i < maxTicks; i++) {
            outcome = watchdog.tick();
            if (outcome != MovementWatchdog.Outcome.NONE) {
                return outcome;
            }
        }
        return outcome;
    }

    private String everythingLogged() {
        return String.join("\n", this.log);
    }

    // --- 10. Repeated recoveries stay bounded. ---

    @Test
    void multipleRecoveriesRemainBoundedAndDoNotLoopForever() {
        MovementWatchdog watchdog = watchdog();

        int limitReported = 0;

        // A build that never recovers: it must give up, not spin.
        for (int i = 0; i < (LIMIT + COOLDOWN + 2) * 20; i++) {
            if (watchdog.tick() == MovementWatchdog.Outcome.LIMITATION) {
                limitReported++;
            }
        }

        assertEquals(MovementWatchdog.MAX_RECOVERIES, this.engine.repaths,
                "recovery must be attempted exactly MAX_RECOVERIES times");
        assertTrue(limitReported > 0, "the exhausted watchdog must report the limitation");
    }

    @Test
    void aCooldownPreventsRapidRepeatRecoveries() {
        MovementWatchdog watchdog = watchdog();

        tickUntil(watchdog, LIMIT + 5);
        assertEquals(1, this.engine.repaths);

        // During the cooldown the engine must not be asked again.
        for (int i = 0; i < COOLDOWN; i++) {
            watchdog.tick();
        }
        assertEquals(1, this.engine.repaths, "cooldown must suppress immediate re-recovery");
    }

    // --- 11. Unavailable safe recovery is reported, not invented. ---

    @Test
    void whenSafeRepathIsUnavailableTheLimitationIsReported() {
        MovementWatchdog watchdog = watchdog();
        this.engine.repathAvailable = false;

        MovementWatchdog.Outcome outcome = tickUntil(watchdog, LIMIT + 5);

        assertEquals(MovementWatchdog.Outcome.LIMITATION, outcome,
                "with no safe recovery the watchdog must report the limitation");
        assertEquals(0, this.engine.repaths);
        assertTrue(everythingLogged().contains("declined the re-plan"),
                "the limitation must be logged, not silently swallowed");
    }

    @Test
    void aThrowingRepathIsReportedRatherThanPropagated() {
        MovementWatchdog watchdog = new MovementWatchdog(
                new MovementWatchdog.Engine() {
                    @Override
                    public boolean isRunning() {
                        return true;
                    }

                    @Override
                    public boolean isPaused() {
                        return false;
                    }

                    @Override
                    public boolean isPathing() {
                        return false;
                    }

                    @Override
                    public String goal() {
                        return "GoalBlock";
                    }

                    @Override
                    public String goalIdentity() {
                        return "GoalBlock@10,64,10";
                    }

                    @Override
                    public String pathDestination() {
                        return "none";
                    }

                    @Override
                    public boolean beginRepath() {
                        throw new IllegalStateException("engine gone");
                    }

                    @Override
                    public void finishRepath() {
                        // never reached
                    }
                },
                this.world, () -> false, this.log::add, LIMIT, COOLDOWN);
        watchdog.begin();

        MovementWatchdog.Outcome outcome = tickUntil(watchdog, LIMIT + 5);

        assertEquals(MovementWatchdog.Outcome.LIMITATION, outcome,
                "a throwing engine must be reported, never crash the build loop");
        assertTrue(everythingLogged().contains("repath request failed"));
    }

    // --- Bookkeeping guards. ---

    @Test
    void anIdleGameNeverAccumulatesStallTicks() {
        MovementWatchdog watchdog = new MovementWatchdog(this.engine, this.world,
                () -> false, this.log::add, LIMIT, COOLDOWN);

        // No begin(): the watchdog is not watching a build.
        for (int i = 0; i < LIMIT * 3; i++) {
            assertEquals(MovementWatchdog.Outcome.NONE, watchdog.tick());
        }

        assertEquals(0, this.engine.repaths);
        assertFalse(watchdog.isWatching());
    }

    @Test
    void anActivePathCalculationIsNotAStall() {
        MovementWatchdog watchdog = watchdog();
        this.engine.pathing = true;

        MovementWatchdog.Outcome outcome = tickUntil(watchdog, LIMIT * 4);

        assertEquals(MovementWatchdog.Outcome.NONE, outcome,
                "a long path calculation looks stationary but is real work");
        assertEquals(0, this.engine.repaths);
    }

    @Test
    void describeReportsTheStateThatIdentifiesAStall() {
        MovementWatchdog watchdog = watchdog();
        for (int i = 0; i < LIMIT - 1; i++) {
            watchdog.tick();
        }

        String summary = watchdog.describe();
        assertTrue(summary.contains("active=true"), summary);
        assertTrue(summary.contains("paused=false"), summary);
        assertTrue(summary.contains("pathing=false"), summary);
        assertTrue(summary.contains("remaining=50"), summary);
        assertTrue(summary.contains("recoveries=0/" + MovementWatchdog.MAX_RECOVERIES), summary);

        // The counter is reported as "n/limit" and must be climbing towards the
        // threshold - that rising number is what identifies a stall in the log.
        assertTrue(summary.matches(".*stationaryTicks=[1-9]/" + LIMIT + ".*"), summary);
    }

    // --- 4. Goal/path signals must count as progress, not as a stall. ---

    @Test
    void aStationaryPlayerWithAChangingGoalIsNotAStall() {
        // The engine can genuinely re-plan while the player stands still.
        // Counting that as a stall is a false positive the old position-only
        // check could produce.
        this.watchdog = watchdog();

        MovementWatchdog.Outcome outcome = MovementWatchdog.Outcome.NONE;

        for (int i = 0; i < LIMIT * 3; i++) {
            this.engine.goalIdentity = "GoalBlock@" + (10 + i) + ",64,10";
            outcome = this.watchdog.tick();

            assertEquals(MovementWatchdog.Outcome.NONE, outcome,
                    "a moving goal means the engine is working, not stalled (tick " + i + ")");
        }

        assertEquals(0, this.engine.repaths, "re-targeting is progress, never a stall");
    }

    @Test
    void aStationaryPlayerWithAChangingPathDestinationIsNotAStall() {
        this.watchdog = watchdog();

        MovementWatchdog.Outcome outcome = MovementWatchdog.Outcome.NONE;

        for (int i = 0; i < LIMIT * 3; i++) {
            this.engine.destination = "(" + (10 + i) + ",64,10)";
            outcome = this.watchdog.tick();
        }

        assertEquals(MovementWatchdog.Outcome.NONE, outcome,
                "a changing path destination is real progress while standing still");
        assertEquals(0, this.engine.repaths);
    }

    @Test
    void unavailableStateIsNotCountedAsAStall() {
        // A null position or unreadable count is missing evidence, not evidence
        // of a stall. Counting it would fire spurious recoveries.
        this.watchdog = watchdog();
        this.world.position = null;

        MovementWatchdog.Outcome outcome = MovementWatchdog.Outcome.NONE;

        for (int i = 0; i < LIMIT * 3; i++) {
            outcome = this.watchdog.tick();
        }

        assertEquals(MovementWatchdog.Outcome.NONE, outcome);
        assertEquals(0, this.watchdog.stationaryTicks(),
                "an unreadable position must not accumulate stall ticks");
        assertEquals(0, this.engine.repaths);
    }

    // --- 2. The diagnostics a live stall actually needs. ---

    @Test
    void stallDiagnosticsNameTheTargetAndNotJustTheGoalClass() {
        this.watchdog = watchdog();

        for (int i = 0; i < 100; i++) {
            this.watchdog.tick();
        }

        String log = everythingLogged();

        assertTrue(log.contains("stationary"), "the stall must be announced: " + log);
        assertTrue(log.contains("remaining=50"), "must name what is outstanding: " + log);
        assertTrue(log.contains("goalIdentity=GoalBlock@10,64,10"),
                "must name the actual target, not just the goal class: " + log);
        assertTrue(log.contains("pathDestination=none"),
                "must report whether a path exists: " + log);
        assertTrue(log.contains("active=true") && log.contains("paused=false"),
                "must report the engine's own state: " + log);
    }

    @Test
    void stallDiagnosticsAreNotLoggedEveryTick() {
        this.watchdog = watchdog();

        for (int i = 0; i < LIMIT * 4; i++) {
            this.watchdog.tick();
        }

        long stationaryLines = this.log.stream().filter(line -> line.contains("stationary ")).count();

        // A few milestones, not one per tick. The live log's 2,367 re-plans were
        // largely this same mistake repeated every tick.
        assertTrue(stationaryLines <= 4,
                "stationary notes must be milestone-only, not per tick, but got " + stationaryLines);
    }

    // --- 6. Recovery continues the build; it never restarts it. ---

    @Test
    void theBuildRemainsActiveThroughoutRecovery() {
        this.watchdog = watchdog();
        stallUntilReported(LIMIT * 6);

        assertTrue(this.engine.running,
                "recovery must never end the build - it is a recovery, not a restart");
        assertTrue(this.watchdog.isWatching(),
                "the watchdog must still be watching the same build");
    }

    @Test
    void anUnchangedGoalExhaustsTheBudgetAndThenGivesUp() {
        this.watchdog = watchdog();

        // Nothing ever changes: the engine keeps re-deriving the same target.
        MovementWatchdog.Outcome outcome = MovementWatchdog.Outcome.NONE;

        for (int i = 0; i < LIMIT * 40 && outcome != MovementWatchdog.Outcome.LIMITATION; i++) {
            outcome = this.watchdog.tick();
        }

        assertEquals(MovementWatchdog.Outcome.LIMITATION, outcome,
                "a build that never advances must end in a bounded, reported give-up");
        assertEquals(MovementWatchdog.MAX_RECOVERIES, this.watchdog.recoveries(),
                "the recovery budget must be spent exactly once each");
        assertTrue(this.engine.running, "giving up must not destroy the build");
    }
}


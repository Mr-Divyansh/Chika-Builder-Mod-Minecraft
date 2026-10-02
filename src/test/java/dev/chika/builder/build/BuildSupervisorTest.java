package dev.chika.builder.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the runtime recovery loop.
 *
 * <p>These cover the exact live failure: the engine pauses itself mid-build
 * ("Missing materials" / "Unable to do it. Pausing."), and the supervisor must
 * supply what is missing, resume the engine, and only report {@code PAUSED}
 * when supplying genuinely cannot make progress.
 */
class BuildSupervisorTest {

    /** The engine side of the supervisor, driven directly by the test. */
    private static final class FakeEngine implements BuildSupervisor.BuildControl {

        boolean running = true;
        boolean paused;
        int resumes;

        @Override
        public boolean isRunning() {
            return this.running;
        }

        @Override
        public boolean isPaused() {
            return this.paused;
        }

        @Override
        public void resume() {
            this.resumes++;
            this.paused = false;
        }
    }

    /** Supply results are scripted per call; nothing is delivered by default. */
    private static final class FakeSupply implements BuildSupervisor.Supply {

        int next;
        int calls;

        FakeSupply delivering(int items) {
            this.next = items;
            return this;
        }

        @Override
        public int supplyOutstanding() {
            this.calls++;
            return this.next;
        }
    }

    /** Outstanding-block verification result. */
    private static final class FakeProgress implements BuildSupervisor.Progress {

        int outstanding;
        boolean fail;

        @Override
        public int outstandingBlocks() {
            if (this.fail) {
                throw new IllegalStateException("world cannot be read");
            }
            return this.outstanding;
        }
    }

    private final FakeEngine engine = new FakeEngine();
    private final FakeSupply supply = new FakeSupply();
    private final FakeProgress progress = new FakeProgress();

    private BuildSupervisor supervisor() {
        return new BuildSupervisor(this.engine, this.supply, this.progress);
    }

    @Test
    void nothingRunsBeforeTheBuildIsSupervised() {
        BuildSupervisor supervisor = supervisor();

        assertFalse(supervisor.tick());
        assertEquals(BuildSupervisor.Status.IDLE, supervisor.status());
        assertEquals(0, this.supply.calls, "supply must not run without a build");
    }

    @Test
    void aHealthyRunningBuildIsLeftAlone() {
        BuildSupervisor supervisor = supervisor();
        supervisor.begin();

        assertFalse(supervisor.tick());
        assertEquals(BuildSupervisor.Status.RUNNING, supervisor.status());
        assertEquals(0, this.supply.calls, "a moving build must not be re-supplied");
        assertEquals(0, this.engine.resumes);
    }

    @Test
    void anEnginePauseIsSuppliedAndResumed() {
        // The live bug: the engine prints "Missing materials" and pauses itself.
        this.engine.paused = true;
        this.supply.delivering(64);

        BuildSupervisor supervisor = supervisor();
        supervisor.begin();

        assertTrue(supervisor.tick(), "recovering from a pause is a reportable change");
        assertEquals(1, this.supply.calls, "the shortfall must actually be supplied");
        assertEquals(1, this.engine.resumes, "supply must be followed by a resume");
        assertEquals(BuildSupervisor.Status.RUNNING, supervisor.status());
        assertEquals(0, supervisor.attempts(), "success resets the retry budget");
        assertFalse(this.engine.paused);
    }

    @Test
    void fruitlessSupplyEndsInAPauseInsteadOfSpinningForever() {
        this.engine.paused = true;
        this.supply.delivering(0); // nothing ever arrives

        BuildSupervisor supervisor = supervisor();
        supervisor.begin();

        for (int i = 0; i < BuildSupervisor.MAX_SUPPLY_ATTEMPTS; i++) {
            assertFalse(supervisor.tick(), "retry " + (i + 1) + " must not give up early");
            assertTrue(this.engine.paused, "the engine stays paused while supply fails");
        }

        assertTrue(supervisor.tick(), "the budget must eventually be reported");
        assertEquals(BuildSupervisor.Status.PAUSED, supervisor.status());
        assertFalse(supervisor.isActive());
        assertEquals(BuildSupervisor.MAX_SUPPLY_ATTEMPTS, this.supply.calls,
                "supply is tried exactly the configured number of times");
        assertEquals(0, this.engine.resumes, "a failed supply must never resume the engine");
    }

    @Test
    void aThrowingSupplierIsTreatedAsNoSupplyAndNeverCrashesTheTick() {
        this.engine.paused = true;

        BuildSupervisor supervisor = new BuildSupervisor(this.engine, () -> {
            throw new RuntimeException("supplier exploded");
        }, this.progress);
        supervisor.begin();

        assertFalse(supervisor.tick());
        assertEquals(1, supervisor.attempts());
        assertEquals(0, this.engine.resumes);
        assertTrue(this.engine.paused);
    }

    @Test
    void aSuccessfulSupplyAfterFailuresResetsTheBudget() {
        this.engine.paused = true;

        BuildSupervisor supervisor = supervisor();
        supervisor.begin();

        this.supply.delivering(0);
        assertFalse(supervisor.tick()); // attempt 1 fails

        this.supply.delivering(12); // the items finally arrive
        assertTrue(supervisor.tick());
        assertEquals(1, this.engine.resumes);
        assertEquals(0, supervisor.attempts());
        assertEquals(BuildSupervisor.Status.RUNNING, supervisor.status());

        // The budget restarts: one more fruitless retry must not end the build.
        this.engine.paused = true;
        this.supply.delivering(0);
        assertFalse(supervisor.tick());
        assertEquals(1, supervisor.attempts());
    }

    @Test
    void healthAfterAPauseRestoresTheRetryBudget() {
        this.engine.paused = true;
        this.supply.delivering(0);

        BuildSupervisor supervisor = supervisor();
        supervisor.begin();
        assertFalse(supervisor.tick()); // attempt 1

        this.engine.paused = false; // engine recovered on its own
        assertFalse(supervisor.tick());
        assertEquals(0, supervisor.attempts(),
                "a healthy tick clears stale attempts so an old failure cannot "
                        + "shorten a later recovery");
        assertEquals(BuildSupervisor.Status.RUNNING, supervisor.status());
    }

    @Test
    void aFinishedBuildWithNothingLeftIsComplete() {
        this.engine.running = false;
        this.progress.outstanding = 0;

        BuildSupervisor supervisor = supervisor();
        supervisor.begin();

        assertTrue(supervisor.tick());
        assertEquals(BuildSupervisor.Status.COMPLETE, supervisor.status());
        assertFalse(supervisor.isActive());
        assertEquals(0, this.supply.calls,
                "completion is decided by the world, not by supplying more");
    }

    @Test
    void aFinishedBuildWithWorkLeftPausesInsteadOfClaimingSuccess() {
        this.engine.running = false;
        this.progress.outstanding = 42;

        BuildSupervisor supervisor = supervisor();
        supervisor.begin();

        assertTrue(supervisor.tick());
        assertEquals(BuildSupervisor.Status.PAUSED, supervisor.status());
    }

    @Test
    void verificationFailureNeverClaimsSuccess() {
        this.engine.running = false;
        this.progress.fail = true;

        BuildSupervisor supervisor = supervisor();
        supervisor.begin();

        assertTrue(supervisor.tick());
        assertEquals(BuildSupervisor.Status.PAUSED, supervisor.status(),
                "if the world cannot be read the build must not be declared done");
    }

    @Test
    void endStopsSupervising() {
        BuildSupervisor supervisor = supervisor();
        supervisor.begin();
        assertTrue(supervisor.isActive());

        supervisor.end();
        assertFalse(supervisor.isActive());
        assertEquals(BuildSupervisor.Status.IDLE, supervisor.status());
        assertFalse(supervisor.tick());
    }
}
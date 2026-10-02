package dev.chika.builder.platform.engine;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import dev.chika.builder.build.BuildException;
import dev.chika.builder.build.BuildService;
import net.minecraft.core.Vec3i;

import java.io.File;

/**
 * {@link BuildService} backed by the internal build engine.
 *
 * <p>This class is the <em>only</em> place that knows which engine is running.
 * Movement, pathing, placement verification and "skip blocks that already
 * match" are all delegated to the engine's own builder process rather than
 * reimplemented here.
 *
 * <p>The engine is an implementation detail: its name never reaches the player
 * through any message produced here, and no Chika Builder class outside this
 * one imports engine packages.
 */
public final class ChikaBuildService implements BuildService {

    @Override
    public String name() {
        // Internal identifier only. Never displayed to the player.
        return "internal-engine";
    }

    @Override
    public boolean isAvailable() {
        try {
            return engine() != null;
        } catch (Throwable t) {
            // Engine classes are present but not initialised yet.
            return false;
        }
    }

    @Override
    public void startBuild(File schematic, Origin origin) throws BuildException {
        if (!schematic.isFile()) {
            throw new BuildException("Schematic file not found: " + schematic.getName());
        }

        IBaritone engine;
        try {
            engine = engine();
        } catch (Throwable t) {
            throw new BuildException("The builder is not available yet.", t);
        }

        if (engine == null) {
            throw new BuildException("The builder is not available yet.");
        }

        try {
            applyBuildSettings();
        } catch (Throwable t) {
            throw new BuildException("Could not apply build settings.", t);
        }

        Vec3i anchor = new Vec3i(origin.x(), origin.y(), origin.z());

        try {
            boolean started = engine.getBuilderProcess().build(schematic.getName(), schematic, anchor);
            if (!started) {
                throw new BuildException("Could not load '" + schematic.getName()
                        + "'. Is it a valid .schematic file?");
            }
        } catch (BuildException e) {
            throw e;
        } catch (Throwable t) {
            throw new BuildException("Failed to start build: " + describe(t), t);
        }
    }

    @Override
    public boolean isBuilding() {
        try {
            IBaritone engine = engine();
            return engine != null && engine.getBuilderProcess() != null
                    && engine.getBuilderProcess().isActive();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isPaused() {
        try {
            IBaritone engine = engine();
            return engine != null && engine.getBuilderProcess() != null
                    && engine.getBuilderProcess().isActive()
                    && engine.getBuilderProcess().isPaused();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True while the engine is actively moving or calculating a path.
     *
     * <p>Read-only diagnostics for the movement watchdog. A long path
     * calculation looks exactly like standing still from outside, so the
     * watchdog needs this to avoid "recovering" a build that is really working.
     */
    @Override
    public boolean isPathing() {
        try {
            IBaritone engine = engine();
            if (engine == null || engine.getPathingBehavior() == null) {
                return false;
            }
            return engine.getPathingBehavior().isPathing()
                    || engine.getPathingBehavior().getInProgress().isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The engine's current movement goal, for stall diagnostics. */
    @Override
    public String describeGoal() {
        try {
            IBaritone engine = engine();
            if (engine == null || engine.getPathingBehavior() == null) {
                return "unavailable";
            }
            var goal = engine.getPathingBehavior().getGoal();
            return goal == null ? "none" : goal.getClass().getSimpleName();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /**
     * A goal identity that changes when the engine picks a <b>different</b> target.
     *
     * <p>{@link #describeGoal()} only returns the goal's class name. A live log
     * showed that staying useless on its own: the goal was
     * {@code JankyGoalComposite} on every one of three re-plans while the build
     * was frozen, because the class was the same even though the engine kept
     * re-deriving the very same break-then-place target.
     *
     * <p>{@code toString()} is used instead because the engine's own goal types
     * embed their target position in it, so this genuinely differs when a
     * different block is being aimed at. Verified against the engine bytecode:
     * {@code JankyGoalComposite.toString()} concatenates its two wrapped goals,
     * and {@code GoalBreak}/{@code GoalPlace} carry their {@code BlockPos}.
     */
    @Override
    public String describeGoalIdentity() {
        try {
            IBaritone engine = engine();
            if (engine == null || engine.getPathingBehavior() == null) {
                return "unavailable";
            }
            var goal = engine.getPathingBehavior().getGoal();
            return goal == null ? "none" : String.valueOf(goal);
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /**
     * Where the engine's current path is heading, or {@code "none"}.
     *
     * <p>Read from the engine's own {@code IPath}, which exposes
     * {@code getDest()}. Used as a second progress signal: a re-plan that
     * produces a genuinely different destination is real forward progress even
     * while the player has not moved yet.
     */
    @Override
    public String describePathDestination() {
        try {
            IBaritone engine = engine();
            if (engine == null || engine.getPathingBehavior() == null) {
                return "none";
            }
            return engine.getPathingBehavior().getPath()
                    .map(path -> String.valueOf(path.getDest()))
                    .orElse("none");
        } catch (Throwable t) {
            return "none";
        }
    }

    /**
     * Phase 1 of a re-plan: pause the builder so the engine cancels its path.
     *
     * <p><b>Why pause/resume and not {@code cancelEverything()}.</b> Both
     * {@code IPathingBehavior.cancelEverything()} and {@code forceCancel()}
     * route through the engine's internal {@code PathingControlManager}, which
     * calls {@code onLostControl()} on every registered process. On
     * {@code BuilderProcess} that method nulls the schematic field, and
     * {@code isActive()} is implemented as {@code schematic != null} - so those
     * calls would silently discard the build and make the supervisor believe it
     * had finished. Verified against the engine bytecode, not assumed.
     *
     * <p>{@code pause()} is a single boolean. On the engine's next tick it
     * returns {@code CANCEL_AND_SET_GOAL}, which is what cancels the current
     * path. {@link #finishRepath()} releases it on a later tick.
     */
    @Override
    public boolean beginRepath() {
        try {
            IBaritone engine = engine();
            if (engine == null || engine.getBuilderProcess() == null
                    || !engine.getBuilderProcess().isActive()) {
                return false;
            }
            engine.getBuilderProcess().pause();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Phase 2 of a re-plan: release the pause so the engine re-plans.
     *
     * <p>Intentionally a later tick than {@link #beginRepath()}. The schematic is
     * untouched, already-placed blocks are kept, and the player is never moved:
     * the engine's own pathfinder does the work under normal collision rules.
     */
    @Override
    public void finishRepath() {
        try {
            IBaritone engine = engine();
            if (engine == null || engine.getBuilderProcess() == null
                    || !engine.getBuilderProcess().isActive()) {
                return;
            }
            engine.getBuilderProcess().resume();
        } catch (Throwable ignored) {
            // Nothing to release.
        }
    }

    @Override
    public void resume() {
        try {
            IBaritone engine = engine();
            if (engine != null && engine.getBuilderProcess() != null) {
                engine.getBuilderProcess().resume();
            }
        } catch (Throwable ignored) {
            // Nothing to resume.
        }
    }

    @Override
    public boolean cancel() {
        try {
            IBaritone engine = engine();
            if (engine != null && engine.getBuilderProcess() != null
                    && engine.getBuilderProcess().isActive()) {
                engine.getBuilderProcess().pause();
                return true;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return false;
    }

    /**
     * Tunes the engine's builder settings for accurate, resumable construction:
     * skip blocks that already match, build in layers for stable movement, and
     * report completion.
     */
    private void applyBuildSettings() {
        Settings settings = BaritoneAPI.getSettings();

        // *** THE ROOT CAUSE OF THE LIVE MOVEMENT FREEZE. ***
        //
        // A live log (FastClient profile 26-1-2, 16:28-16:30) showed the build
        // frozen with the engine holding `JankyGoalComposite` and `remaining=52`
        // for minutes, on every one of three "recoveries".
        //
        // Reading BuilderProcess.onTick's bytecode explains it. With
        // buildIgnoreExisting=false, a schematic cell whose world block is
        // non-air but does not match is NOT skipped: the engine builds a
        // `GoalBreak` for it and wraps it with the placement goal in a
        // `JankyGoalComposite` (see the two `new JankyGoalComposite` sites in
        // onTick). It then tries to MINE that block. If the player cannot reach
        // or break it, the engine re-derives the identical goal on every tick,
        // never places anything, and never pauses - so the build looks alive
        // (`isActive()` is `schematic != null`) while doing nothing.
        //
        // true means "a cell that already holds a block is left alone", which
        // is exactly right for placing a fresh house and removes the mining
        // goal from the loop entirely. It is a supported engine setting; the
        // jar is untouched.
        settings.buildIgnoreExisting.value = true;

        // Accept placement orientation, exactly the way the engine compares
        // "already built". The state a player produces by clicking a block
        // depends on where they stand and which way they face, so requiring an
        // exact facing/half/shape/axis can leave a cell unbuildable forever and
        // the engine then reports "Missing materials" for a block that is
        // already in the player's hand. This is a supported engine option, set
        // here rather than by touching the engine jar.
        settings.buildIgnoreDirection.value = true;

        // Layered building keeps the engine's movement stable and efficient.
        settings.buildInLayers.value = true;
        // Re-check blocks around the build so mis-placed blocks get corrected.
        settings.builderTickScanRadius.value = 1;
        // Surface a message when the build finishes - in chat, through our own
        // branded sink. The engine's *desktop* notification helper is left off:
        // it posts an OS tray toast titled with the engine's name, which would
        // put third-party branding on screen outside our control.
        settings.notificationOnBuildFinished.value = false;
    }

    private static IBaritone engine() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            return t.getClass().getSimpleName();
        }
        return message;
    }
}
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

        // Accurate placement: do not ignore what is already there, so the
        // engine verifies existing blocks and only fixes the ones that are wrong.
        // (Blocks that already match are skipped, which is the desired behaviour.)
        settings.buildIgnoreExisting.value = false;
        // Layered building keeps the engine's movement stable and efficient.
        settings.buildInLayers.value = true;
        // Surface a message when the build finishes.
        settings.notificationOnBuildFinished.value = true;
        // Re-check blocks around the build so mis-placed blocks get corrected.
        settings.builderTickScanRadius.value = 1;
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
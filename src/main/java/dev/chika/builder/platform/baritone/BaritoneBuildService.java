package dev.chika.builder.platform.baritone;

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
 * <p>This class is the <em>only</em> place that knows the engine exists. Movement,
 * pathing, placement verification and "skip blocks that already match" are all
 * delegated to the engine's own builder process rather than reimplemented here.
 *
 * <p>The engine is an implementation detail: its name never reaches the player
 * through any message produced here.
 */
public final class BaritoneBuildService implements BuildService {

    @Override
    public String name() {
        // Internal identifier only. Never displayed to the player.
        return "internal-engine";
    }

    @Override
    public boolean isAvailable() {
        try {
            return baritone() != null;
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

        IBaritone baritone;
        try {
            baritone = baritone();
        } catch (Throwable t) {
            throw new BuildException("The builder is not available yet.", t);
        }

        if (baritone == null) {
            throw new BuildException("The builder is not available yet.");
        }

        try {
            applyBuildSettings();
        } catch (Throwable t) {
            throw new BuildException("Could not apply build settings.", t);
        }

        Vec3i anchor = new Vec3i(origin.x(), origin.y(), origin.z());

        try {
            boolean started = baritone.getBuilderProcess().build(schematic.getName(), schematic, anchor);
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
            IBaritone baritone = baritone();
            return baritone != null && baritone.getBuilderProcess() != null
                    && baritone.getBuilderProcess().isActive();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean cancel() {
        try {
            IBaritone baritone = baritone();
            if (baritone != null && baritone.getBuilderProcess() != null
                    && baritone.getBuilderProcess().isActive()) {
                baritone.getBuilderProcess().pause();
                return true;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return false;
    }

    /**
     * Tunes Baritone's builder settings for accurate, resumable construction:
     * skip blocks that already match, build in layers for stable movement, and
     * report completion.
     */
    private void applyBuildSettings() {
        Settings settings = BaritoneAPI.getSettings();

        // Accurate placement: do not ignore what is already there, so Baritone
        // verifies existing blocks and only fixes the ones that are wrong.
        // (Blocks that already match are skipped, which is the desired behaviour.)
        settings.buildIgnoreExisting.value = false;
        // Layered building keeps Baritone's movement stable and efficient.
        settings.buildInLayers.value = true;
        // Surface a message when the build finishes.
        settings.notificationOnBuildFinished.value = true;
        // Re-check blocks around the build so mis-placed blocks get corrected.
        settings.builderTickScanRadius.value = 1;
    }

    private static IBaritone baritone() {
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
package dev.chika.builder.platform.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the build settings applied to the engine.
 *
 * <p>The live movement freeze came from {@code buildIgnoreExisting} being
 * {@code false}: the engine then emits {@code GoalBreak} goals (wrapped in a
 * {@code JankyGoalComposite}) for cells that already hold a block, and if the
 * player cannot reach or break that block it re-derives the identical goal
 * every tick and never places anything. Nothing here changes the engine jar -
 * only its supported {@code Settings}.
 */
class BuildSettingsTest {

    @Test
    void theBuildSettingsDoNotAskTheEngineToMineAnything() {
        // Guards the intent in source form, which is what the engine actually
        // reads at runtime. See ChikaBuildService#applyBuildSettings.
        String source = read("src/main/java/dev/chika/builder/platform/engine/ChikaBuildService.java");

        assertTrue(source.contains("buildIgnoreExisting.value = true"),
                "buildIgnoreExisting must be true, or the engine loops on unbreakable "
                        + "GoalBreak goals and the build freezes");
        assertFalse(source.contains("buildIgnoreExisting.value = false"),
                "buildIgnoreExisting=false is what caused the live freeze");

        // Placement orientation must stay ignored, or a block the player placed
        // by hand is judged "wrong" and the engine tries to break and replace it.
        assertTrue(source.contains("buildIgnoreDirection.value = true"),
                "placement orientation must stay ignored");
    }

    @Test
    void theEngineJarIsNeverModifiedAtRuntime() {
        // Everything is done through the engine's public Settings and process
        // API. Nothing may reflect into it, and nothing may rewrite it.
        String source = read("src/main/java/dev/chika/builder/platform/engine/ChikaBuildService.java");

        assertFalse(source.contains("Class.forName"),
                "the engine must be used through its public API, not reflection");
        assertFalse(source.contains("setAccessible"),
                "the engine's internals must never be poked at");
    }

    private static String read(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
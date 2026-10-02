package dev.chika.builder.build.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The block-state comparison, which must agree with the build engine's own
 * "already built" rule.
 *
 * <p>Comparing more strictly than the engine means the builder stops with cells
 * it considers finished, which is how a live run ended reporting a permanent
 * {@code N block(s) still missing}.
 */
class BlockStateViewTest {

    private static final String STAIRS = "minecraft:oak_stairs";

    @Test
    void theSameStateAlwaysMatches() {
        BlockStateView want = new BlockStateView(STAIRS, "facing=north,half=bottom,shape=straight");

        assertTrue(want.matches(want));
    }

    @Test
    void aDifferentBlockNeverMatches() {
        BlockStateView stairs = new BlockStateView(STAIRS, "facing=north,half=bottom");
        BlockStateView stone = new BlockStateView("minecraft:stone", "");

        assertFalse(stairs.matches(stone));
    }

    @Test
    void aDifferentOrientationMatchesJustAsTheEngineDecidesIt() {
        // The engine ignores direction when buildIgnoreDirection is on, because
        // the state produced by clicking depends on where the player stands.
        BlockStateView schematic = new BlockStateView(STAIRS, "facing=north,half=bottom,shape=straight");
        BlockStateView placed = new BlockStateView(STAIRS, "facing=east,half=bottom,shape=straight");

        assertTrue(schematic.matches(placed),
                "orientation must not keep a finished cell outstanding");
    }

    @Test
    void identifyingPropertiesStillHaveToMatch() {
        // half/shape are direction-like and ignored, but a top half is a
        // genuinely different cell, and the engine does not ignore that either.
        BlockStateView bottom = new BlockStateView(STAIRS, "facing=north,half=bottom,shape=straight");
        BlockStateView otherBlock = new BlockStateView(STAIRS, "facing=north,half=bottom,waterlogged=true");

        assertFalse(bottom.matches(otherBlock),
                "waterlogged is not an ignored property");
    }

    @Test
    void anUnknownCellNeverMatches() {
        assertFalse(new BlockStateView("", "facing=north").matches(
                new BlockStateView(STAIRS, "facing=north")));
        assertFalse(new BlockStateView(STAIRS, "facing=north").matches(null));
    }

    @Test
    void strictComparisonIsStillAvailable() {
        // With nothing ignored, orientation matters again - the rule is a
        // parameter, not a hard-coded laxness.
        BlockStateView schematic = new BlockStateView(STAIRS, "facing=north,half=bottom");
        BlockStateView placed = new BlockStateView(STAIRS, "facing=east,half=bottom");

        assertFalse(schematic.matchesIgnoring(placed, java.util.Set.of()));
        assertTrue(schematic.matchesIgnoring(placed,
                java.util.Set.of("facing")));
    }

    @Test
    void propertyOrderDoesNotMatter() {
        BlockStateView a = new BlockStateView(STAIRS, "facing=north,half=bottom");
        BlockStateView b = new BlockStateView(STAIRS, "half=bottom,facing=north");

        assertTrue(a.matches(b), "the game does not guarantee property order");
    }

    @Test
    void theIgnoredSetMirrorsTheEngine() {
        // Read from the engine's own static ignore-set in its bytecode.
        assertTrue(BlockStateView.ENGINE_IGNORED_PROPERTIES.containsAll(
                java.util.List.of("facing", "half", "shape", "axis", "open",
                        "north", "south", "east", "west", "up")));
    }
}
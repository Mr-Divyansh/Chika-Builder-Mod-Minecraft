package dev.chika.builder.build.material;

/**
 * How a schematic cell is compared against the world.
 *
 * <p>A cell counts as already satisfied when the block <b>type</b> matches and
 * every property that actually identifies the block matches too.
 *
 * <p><b>Why directional properties are excluded.</b> The build engine decides
 * "already built" with a comparator that, once its {@code buildIgnoreDirection}
 * option is on, skips direction/rotation properties (read from the engine's own
 * bytecode: stairs' {@code facing}/{@code half}/{@code shape}, a pillar's
 * {@code axis}, a horizontal block's {@code facing}, pipes' side flags and a
 * trapdoor's {@code open}). That is deliberate on its side: the state a player
 * produces by clicking a block depends on where they stand and which way they
 * face, so demanding an exact orientation can leave a cell unbuildable forever.
 * Comparing more strictly than the engine does means the builder finishes its
 * work and Chika Builder still counts those cells as outstanding - which is
 * exactly how a live run ended reporting a permanent {@code N block(s) still
 * missing} on a schematic the engine considered complete. Both sides must use
 * the same rule.
 *
 * <p>Plain data rather than Minecraft types, so the comparison rule is unit
 * testable without a running game. {@code ExistingBlockChecker} builds the views
 * from real block states.
 *
 * @param blockId    registry id of the block, e.g. {@code minecraft:oak_stairs}
 * @param properties canonical property string, e.g.
 *                   {@code "facing=east,half=bottom"}; empty when the block has
 *                   no properties
 */
public record BlockStateView(String blockId, String properties) {

    /**
     * Properties the engine ignores when it decides whether a cell is already
     * correct, named exactly as the engine's own static ignore-set names them.
     */
    public static final java.util.Set<String> ENGINE_IGNORED_PROPERTIES =
            java.util.Set.of("facing", "half", "shape", "axis", "open",
                    "north", "south", "east", "west", "up");

    public BlockStateView {
        blockId = blockId == null ? "" : blockId;
        properties = properties == null ? "" : properties;
    }

    /**
     * True when this cell is what the schematic asks for, compared the same way
     * the engine compares it.
     *
     * <p>Block type and every identifying property must match, except the
     * properties in {@link #ENGINE_IGNORED_PROPERTIES}. A {@code null} or empty
     * block id never matches, because "unknown" is not the same as "already
     * correct" and treating it as correct would silently skip a block.
     */
    public boolean matches(BlockStateView other) {
        return matchesIgnoring(other, ENGINE_IGNORED_PROPERTIES);
    }

    /**
     * True when this cell matches {@code other} on block type and on every
     * property not named in {@code ignored}.
     *
     * <p>The comparison is symmetric: a property that only one of the two states
     * declares still has to agree, otherwise a cell that is waterlogged in the
     * world but dry in the schematic (or vice versa) would pass unnoticed. For
     * two states of the same block - the only case the engine ever compares -
     * both sides always declare the same properties, so this is exactly its rule.
     *
     * <p>Exposed so the rule can be exercised directly, including strictly
     * (empty ignore set) and with a wider ignore set.
     */
    public boolean matchesIgnoring(BlockStateView other, java.util.Set<String> ignored) {
        if (other == null || this.blockId.isEmpty() || other.blockId.isEmpty()) {
            return false;
        }

        if (!this.blockId.equals(other.blockId)) {
            return false;
        }

        java.util.Map<String, String> mine = propertyMap(this.properties);
        java.util.Map<String, String> theirs = propertyMap(other.properties);

        java.util.Set<String> names = new java.util.LinkedHashSet<>(mine.keySet());
        names.addAll(theirs.keySet());

        for (String name : names) {
            if (ignored != null && ignored.contains(name)) {
                continue;
            }

            String value = mine.get(name);

            if (value == null || !value.equals(theirs.get(name))) {
                return false;
            }
        }

        return true;
    }

    /** Splits a canonical property string into a comparable map. */
    private static java.util.Map<String, String> propertyMap(String properties) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();

        if (properties == null || properties.isEmpty()) {
            return values;
        }

        for (String pair : properties.split(",")) {
            int equals = pair.indexOf('=');

            if (equals > 0) {
                values.put(pair.substring(0, equals), pair.substring(equals + 1));
            }
        }

        return values;
    }
}
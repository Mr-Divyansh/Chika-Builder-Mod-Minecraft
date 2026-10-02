package dev.chika.builder.build.material;

/**
 * How a schematic cell is compared against the world.
 *
 * <p>A cell counts as already satisfied only when the block <b>type</b> and the
 * full <b>block state</b> both match. Comparing the type alone is not enough: a
 * stair facing north when the schematic asks for it facing east is the wrong
 * block for this build, and treating it as done would leave the structure visibly
 * wrong while reporting success.
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

    public BlockStateView {
        blockId = blockId == null ? "" : blockId;
        properties = properties == null ? "" : properties;
    }

    /**
     * True when this cell is exactly what the schematic asks for.
     *
     * <p>Both parts must match. A {@code null} or empty block id never matches,
     * because "unknown" is not the same as "already correct" and treating it as
     * correct would silently skip a block.
     */
    public boolean matches(BlockStateView other) {
        if (other == null || this.blockId.isEmpty() || other.blockId.isEmpty()) {
            return false;
        }
        return this.blockId.equals(other.blockId)
                && this.properties.equals(other.properties);
    }
}
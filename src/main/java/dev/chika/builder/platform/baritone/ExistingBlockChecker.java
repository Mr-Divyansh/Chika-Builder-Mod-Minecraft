package dev.chika.builder.platform.baritone;

import baritone.api.schematic.IStaticSchematic;
import dev.chika.builder.build.material.MaterialNeed;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts the schematic blocks that are already correct in the world.
 *
 * <p>This is the mechanism that makes a resumed build cheap: blocks placed
 * before the pause are recognised as done, so they are never re-requested from
 * the inventory, never re-bought, and never re-placed. Only the genuinely
 * outstanding blocks are worked on.
 *
 * <p>Kept separate from the analyzer because it needs a live {@link Level},
 * which does not exist when the file is first inspected.
 */
public final class ExistingBlockChecker {

    private ExistingBlockChecker() {
    }

    /**
     * Rewrites {@code needs} with the number of each material already placed.
     *
     * <p>When no world is loaded (or the schematic cannot be re-read) the counts
     * stay at zero, which is safe: the build simply asks for everything, and the
     * engine still skips correct blocks when it places them.
     *
     * @param needs    requirements from the schematic analysis
     * @param schematic the parsed schematic
     * @param origin   world position of the schematic's (0,0,0) corner
     */
    public static List<MaterialNeed> apply(List<MaterialNeed> needs, IStaticSchematic schematic,
                                           dev.chika.builder.build.BuildService.Origin origin) {

        Level level = currentLevel();

        if (level == null || origin == null || schematic == null || needs.isEmpty()) {
            return needs;
        }

        // Resolve the distinct blocks we actually care about first, so a large
        // schematic is walked exactly once instead of once per material.
        Map<Block, Integer> wanted = new HashMap<>();

        for (MaterialNeed need : needs) {
            Block block = ItemIds.blockFrom(need.item().itemId());

            if (block != null) {
                wanted.putIfAbsent(block, 0);
            }
        }

        if (wanted.isEmpty()) {
            return needs;
        }

        Map<Block, Integer> correct = countCorrect(schematic, wanted,
                new BlockPos(origin.x(), origin.y(), origin.z()), level);

        List<MaterialNeed> updated = new ArrayList<>(needs.size());

        for (MaterialNeed need : needs) {
            Block block = ItemIds.blockFrom(need.item().itemId());

            int placed = block == null ? 0 : correct.getOrDefault(block, 0);

            updated.add(new MaterialNeed(need.item(), need.required(), placed,
                    need.inInventory(), need.resolution()));
        }

        return updated;
    }

    /** The loaded level, or {@code null} outside a world. */
    private static Level currentLevel() {
        try {
            return net.minecraft.client.Minecraft.getInstance().level;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * One traversal of the schematic, tallying how many cells of each wanted
     * block are already correct in the world.
     */
    private static Map<Block, Integer> countCorrect(IStaticSchematic schematic,
                                                    Map<Block, Integer> wanted,
                                                    BlockPos origin, Level level) {

        Map<Block, Integer> correct = new HashMap<>();

        for (int x = 0; x < schematic.widthX(); x++) {
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    BlockState expected = schematic.getDirect(x, y, z);

                    if (expected == null) {
                        continue;
                    }

                    Block block = expected.getBlock();

                    // Not a material this build needs - do not touch the world.
                    if (!wanted.containsKey(block)) {
                        continue;
                    }

                    BlockState actual = level.getBlockState(origin.offset(x, y, z));

                    if (actual != null && actual.getBlock().equals(block)) {
                        correct.merge(block, 1, Integer::sum);
                    }
                }
            }
        }

        return correct;
    }
}

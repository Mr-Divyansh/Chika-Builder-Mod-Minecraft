package dev.chika.builder.platform.engine;

import baritone.api.BaritoneAPI;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import dev.chika.builder.build.material.ItemAmount;
import dev.chika.builder.build.material.MaterialNeed;
import dev.chika.builder.build.material.MaterialResolution;
import dev.chika.builder.build.material.SchematicAnalyzer;
import dev.chika.builder.build.BuildService;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads schematic files using the build engine's own schematic parser.
 *
 * <p>Reusing the engine's parser means {@code #chika_build} and the material
 * calculation can never disagree about what a file contains, and every format
 * the engine supports (WorldEdit, Litematica, ...) works here for free.
 *
 * <p>Air is skipped entirely: it describes what the schematic leaves empty, not
 * a material to acquire. Neither are blocks with no item form, which cannot be
 * supplied from an inventory at all.
 */
public final class ChikaSchematicAnalyzer implements SchematicAnalyzer {

    /** Guard against absurd files; a build this size is not playable anyway. */
    private static final int MAX_VOLUME = 8_000_000;

    @Override
    public List<MaterialNeed> analyze(File schematic, BuildService.Origin origin)
            throws SchematicAnalysisException {

        Optional<ISchematicFormat> format = formatFor(schematic);
        if (format.isEmpty()) {
            throw new SchematicAnalysisException("unsupported schematic format");
        }

        IStaticSchematic parsed;
        try (InputStream in = Files.newInputStream(schematic.toPath())) {
            parsed = format.get().parse(in);
        } catch (Exception e) {
            throw new SchematicAnalysisException("could not parse the file", e);
        }

        if (parsed == null) {
            throw new SchematicAnalysisException("could not parse the file");
        }

        long volume = (long) parsed.widthX() * parsed.heightY() * parsed.lengthZ();
        if (volume > MAX_VOLUME) {
            throw new SchematicAnalysisException("schematic is too large to build");
        }

        List<MaterialNeed> needs = countBlocks(parsed);

        // Subtracts the blocks that are already correct in the world, so a
        // resumed build only asks for what is genuinely still missing.
        return ExistingBlockChecker.apply(needs, parsed, origin);
    }

    /** Walks every cell and tallies the non-air, placeable blocks. */
    private static List<MaterialNeed> countBlocks(IStaticSchematic schematic)
            throws SchematicAnalysisException {

        // LinkedHashMap keeps a stable order so reports read the same each run.
        Map<String, ItemAmount> counts = new LinkedHashMap<>();

        for (int x = 0; x < schematic.widthX(); x++) {
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    BlockState state = schematic.getDirect(x, y, z);

                    if (state == null || state.isAir()) {
                        continue;
                    }

                    Block block = state.getBlock();
                    if (block.asItem() == Items.AIR) {
                        continue;
                    }

                    tally(counts, block);
                }
            }
        }

        List<MaterialNeed> needs = new ArrayList<>(counts.size());
        for (ItemAmount amount : counts.values()) {
            needs.add(new MaterialNeed(amount, amount.amount(), 0, 0, MaterialResolution.MISSING));
        }
        return needs;
    }

    private static Optional<ISchematicFormat> formatFor(File schematic) {
        try {
            return BaritoneAPI.getProvider().getSchematicSystem().getByFile(schematic);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private static void tally(Map<String, ItemAmount> counts, Block block) {
        String id = ItemIds.of(block.asItem());

        if (id == null) {
            return;
        }

        ItemAmount existing = counts.get(id);
        if (existing == null) {
            counts.put(id, new ItemAmount(id, block.getName().getString(), 1));
        } else {
            counts.put(id, new ItemAmount(id, existing.displayName(), existing.amount() + 1));
        }
    }
}

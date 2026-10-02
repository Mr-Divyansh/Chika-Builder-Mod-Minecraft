package dev.chika.builder.build.material;

import java.util.List;

/**
 * The outcome of working out how a schematic's materials will be obtained.
 *
 * <p>Produced by {@link MaterialPlanner}. Pure data: no Minecraft types, so the
 * whole supply chain can be unit tested without a running game.
 *
 * @param needs     one entry per distinct block type, ordered by the priority
 *                  ladder so the player-facing report reads sensibly
 * @param canProceed true when nothing blocks the build from starting
 */
public record MaterialPlan(List<MaterialNeed> needs, boolean canProceed) {

    public MaterialPlan {
        needs = List.copyOf(needs);
    }

    /** Convenience for "everything is covered". */
    public static MaterialPlan satisfied(List<MaterialNeed> needs) {
        return new MaterialPlan(needs, true);
    }

    /** Only the entries that still block the build. */
    public List<MaterialNeed> missing() {
        return this.needs.stream()
                .filter(need -> need.resolution() == MaterialResolution.MISSING)
                .toList();
    }

    /** Only the entries that a shop adapter still has to buy. */
    public List<MaterialNeed> toPurchase() {
        return this.needs.stream()
                .filter(need -> need.resolution() == MaterialResolution.SHOP)
                .toList();
    }

    /**
     * Only the entries Creative still has to hand over.
     *
     * <p>One entry per block type that is genuinely still short, so a partially
     * granted material is never re-requested in full.
     */
    public List<MaterialNeed> toAcquireCreatively() {
        return this.needs.stream()
                .filter(need -> need.resolution() == MaterialResolution.CREATIVE)
                .filter(need -> need.shortfall() > 0)
                .toList();
    }

    /**
     * True when something must be taken from Creative before this build can
     * start, i.e. the plan leans on the Creative rung at all.
     */
    public boolean requiresCreative() {
        return !toAcquireCreatively().isEmpty();
    }

    /** Blocks that already satisfy the schematic, so they are never re-obtained. */
    public int alreadyPlacedCount() {
        return totalOf(MaterialResolution.ALREADY_PLACED);
    }

    /** Distinct block types the schematic needs. */
    public int distinctBlockTypes() {
        return this.needs.size();
    }

    private int totalOf(MaterialResolution resolution) {
        return this.needs.stream()
                .filter(need -> need.resolution() == resolution)
                .mapToInt(MaterialNeed::required)
                .sum();
    }
}

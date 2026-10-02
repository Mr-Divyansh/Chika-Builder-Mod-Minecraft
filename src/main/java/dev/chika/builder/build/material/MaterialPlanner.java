package dev.chika.builder.build.material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Works out how each of a schematic's materials will be obtained.
 *
 * <p>This is the single place the supply priority is decided, and it is pure
 * logic with no Minecraft dependency so every branch is unit testable:
 *
 * <ol>
 *   <li><b>Correct blocks already present</b> - never re-obtained, never bought.</li>
 *   <li><b>Player inventory</b> - uses what the player already has.</li>
 *   <li><b>Creative</b> - only when the creative option is on <i>and</i> the
 *       player is genuinely in Creative.</li>
 *   <li><b>Auto-shop</b> - only when the shop option is on.</li>
 *   <li><b>Otherwise pause</b> - the caller reports what is missing.</li>
 * </ol>
 *
 * <p>Two deliberate safety properties:
 * <ul>
 *   <li>Creative is never assumed. {@code actuallyInCreative} comes from the
 *       client's own gamemode, and a false value means the Creative rung is
 *       skipped even if the option is enabled.</li>
 *   <li>Nothing is planned for purchase that the active method already covers,
 *       so the shop is never asked for blocks the player already owns.</li>
 * </ul>
 */
public final class MaterialPlanner {

    private MaterialPlanner() {
    }

    /**
     * @param requirements     one entry per distinct block type in the schematic
     * @param creativeEnabled  the {@code #chika_builder creative} setting
     * @param actuallyInCreative the player's real gamemode, read from the client
     * @param shopEnabled      the {@code #chika_builder shop} setting
     * @return the plan; {@code canProceed} is false when anything is missing
     */
    public static MaterialPlan plan(List<MaterialNeed> requirements,
                                    boolean creativeEnabled,
                                    boolean actuallyInCreative,
                                    boolean shopEnabled) {

        // Creative only counts when the player really is in Creative.
        boolean creativeUsable = creativeEnabled && actuallyInCreative;

        List<MaterialNeed> resolved = new ArrayList<>(requirements.size());

        for (MaterialNeed need : requirements) {
            resolved.add(resolve(need, creativeUsable, shopEnabled));
        }

        // Stable ordering by the priority ladder, so the report lists the
        // blocking items last and the biggest shortfalls first.
        resolved.sort(Comparator
                .comparingInt((MaterialNeed need) -> need.resolution().ordinal())
                .thenComparing(MaterialNeed::describeOutstanding));

        // A need routed to the shop is NOT satisfied yet: the purchase must
        // happen before the build may start.
        boolean canProceed = resolved.stream().allMatch(MaterialNeed::isSatisfied);
        return new MaterialPlan(resolved, canProceed);
    }

    private static MaterialNeed resolve(MaterialNeed need, boolean creativeUsable, boolean shopEnabled) {

        // 1. The world already satisfies this - nothing to obtain.
        if (need.outstanding() == 0) {
            return withResolution(need, MaterialResolution.ALREADY_PLACED);
        }

        // 2. The player's inventory covers the outstanding amount.
        if (need.shortfall() == 0) {
            return withResolution(need, MaterialResolution.INVENTORY);
        }

        // 3. Creative is an effectively unlimited source, and the engine only
        //    ever asks "is at least one of this block in my storage slots?" - it
        //    builds a list of placeable states by scanning the 36 slots, and in
        //    Creative placing does not consume the stack. So holding a single
        //    one is genuinely enough, and insisting on the full count would
        //    make a schematic unsatisfiable purely because 36 slots cannot hold
        //    every total at once.
        if (creativeUsable && need.inInventory() > 0) {
            return withResolution(need, MaterialResolution.INVENTORY);
        }

        // 4. Creative supplies the rest, but only when actually in Creative.
        if (creativeUsable) {
            return withResolution(need, MaterialResolution.CREATIVE);
        }

        // 5. A shop may buy whatever is still short.
        if (shopEnabled) {
            return withResolution(need, MaterialResolution.SHOP);
        }

        // 6. Unobtainable - the build pauses and reports.
        return withResolution(need, MaterialResolution.MISSING);
    }

    private static MaterialNeed withResolution(MaterialNeed need, MaterialResolution resolution) {
        return new MaterialNeed(need.item(), need.required(), need.alreadyPlaced(),
                need.inInventory(), resolution);
    }
}

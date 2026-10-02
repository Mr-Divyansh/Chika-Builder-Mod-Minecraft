package dev.chika.builder.build.material;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the supply priority ladder:
 * existing blocks -> inventory -> Creative -> shop -> pause.
 */
class MaterialPlannerTest {

    private static final String STONE = "minecraft:stone";
    private static final String PLANKS = "minecraft:oak_planks";

    private static MaterialNeed need(String id, String label, int required,
                                    int placed, int held) {
        return new MaterialNeed(new ItemAmount(id, label, required), required, placed, held,
                MaterialResolution.MISSING);
    }

    private static MaterialNeed need(String id, int required, int placed, int held) {
        return need(id, id, required, placed, held);
    }

    @Test
    void blocksAlreadyInTheWorldNeedNothing() {
        MaterialPlan plan = MaterialPlanner.plan(
                List.of(need(STONE, 248, 248, 0)), false, false, false);

        assertEquals(MaterialResolution.ALREADY_PLACED, plan.needs().get(0).resolution());
        assertTrue(plan.canProceed(), "a fully built schematic must proceed");
        assertEquals(0, plan.needs().get(0).shortfall());
    }

    @Test
    void inventoryCoversTheOutstandingBlocks() {
        // 248 required, 200 already placed, 48 in hand -> nothing missing.
        MaterialPlan plan = MaterialPlanner.plan(
                List.of(need(STONE, 248, 200, 48)), false, false, false);

        assertEquals(MaterialResolution.INVENTORY, plan.needs().get(0).resolution());
        assertTrue(plan.canProceed());
    }

    @Test
    void creativeOnlyAppliesWhenActuallyInCreative() {
        List<MaterialNeed> needed = List.of(need(STONE, 248, 0, 0));

        // Setting on, but the player is NOT in Creative -> must not be granted.
        MaterialPlan notCreative = MaterialPlanner.plan(needed, true, false, false);
        assertEquals(MaterialResolution.MISSING, notCreative.needs().get(0).resolution(),
                "creative must not be assumed when the player is not in Creative");
        assertFalse(notCreative.canProceed());

        // Setting on AND genuinely in Creative -> routed to Creative. It is NOT
        // satisfied yet: the blocks still have to be handed over, so the plan
        // reports that Creative is required instead of claiming it is done.
        MaterialPlan inCreative = MaterialPlanner.plan(needed, true, true, false);
        assertEquals(MaterialResolution.CREATIVE, inCreative.needs().get(0).resolution());
        assertFalse(inCreative.canProceed(),
                "a Creative plan is only satisfied once the blocks are in the inventory");
        assertTrue(inCreative.requiresCreative());
        assertEquals(1, inCreative.toAcquireCreatively().size());
    }

    @Test
    void creativeIsIgnoredWhenTheSettingIsOff() {
        MaterialPlan plan = MaterialPlanner.plan(
                List.of(need(STONE, 248, 0, 0)), false, true, false);

        assertEquals(MaterialResolution.MISSING, plan.needs().get(0).resolution(),
                "being in Creative must not be used unless creative=true");
    }

    @Test
    void shopIsOnlyUsedWhenEnabled() {
        List<MaterialNeed> needed = List.of(need(STONE, 248, 0, 0));

        assertEquals(MaterialResolution.MISSING,
                MaterialPlanner.plan(needed, false, false, false).needs().get(0).resolution(),
                "shop must not be used when disabled");

        assertEquals(MaterialResolution.SHOP,
                MaterialPlanner.plan(needed, false, false, true).needs().get(0).resolution(),
                "shop must be used when enabled");
    }

    @Test
    void creativeBeatsShopInPriority() {
        // Creative is rung 3, shop rung 4: with both on and a real shortage,
        // creative wins and nothing is bought.
        MaterialPlan plan = MaterialPlanner.plan(
                List.of(need(STONE, 248, 0, 0)), true, true, true);

        assertEquals(MaterialResolution.CREATIVE, plan.needs().get(0).resolution());
        assertTrue(plan.toPurchase().isEmpty(),
                "nothing may be bought when Creative already covers the build");
    }

@Test
    void inventoryBeatsCreativeAndShop() {
        MaterialPlan plan = MaterialPlanner.plan(
                List.of(need(STONE, 248, 0, 248)), true, true, true);

        assertEquals(MaterialResolution.INVENTORY, plan.needs().get(0).resolution());
        assertTrue(plan.toPurchase().isEmpty(),
                "items already held must never be bought");
    }

    @Test
    void existingBlocksAreNeverBought() {
        // 248 required, 200 already built: only the remaining 48 are outstanding,
        // so the purchase must be for 48 and never for 248.
        MaterialPlan plan = MaterialPlanner.plan(
                List.of(need(STONE, 248, 200, 0)), false, false, true);

        MaterialNeed stone = plan.needs().get(0);
        assertEquals(48, stone.outstanding());
        assertEquals(48, stone.shortfall());
        assertEquals(MaterialResolution.SHOP, stone.resolution());
    }

    @Test
    void shortfallExcludesBothPlacedBlocksAndInventory() {
        // 248 required, 200 placed, 100 held -> 48 needed, already covered.
        MaterialNeed stone = need(STONE, 248, 200, 100);

        assertEquals(48, stone.outstanding());
        assertEquals(0, stone.shortfall());
    }

    @Test
    void missingMaterialsPauseTheBuild() {
        MaterialPlan plan = MaterialPlanner.plan(
                List.of(need(STONE, 248, 0, 0), need(PLANKS, 96, 0, 0)),
                false, false, false);

        assertFalse(plan.canProceed());
        assertEquals(2, plan.missing().size());
    }

    @Test
    void reportListsOutstandingNotTotalRequirement() {
        // 248 total but 200 already built: the player is told 48 are missing.
        MaterialPlan plan = MaterialPlanner.plan(
                List.of(need(STONE, "Stone", 248, 200, 0)), false, false, false);

        assertEquals("Stone x48", plan.needs().get(0).describeOutstanding());
    }

    @Test
    void mixedSchematicResolvesEachBlockIndependently() {
        MaterialPlan plan = MaterialPlanner.plan(List.of(
                need(STONE, 248, 248, 0),   // fully built
                need(PLANKS, 96, 0, 96),    // fully held
                need("minecraft:glass", 12, 0, 0)), // short
                false, false, false);

        assertEquals(3, plan.distinctBlockTypes());
        assertEquals(1, plan.missing().size());
        assertEquals("minecraft:glass", plan.missing().get(0).item().itemId());
        assertFalse(plan.canProceed(), "one short block type must hold the whole build");
    }

    @Test
    void planIsOrderedByThePriorityLadder() {
        MaterialPlan plan = MaterialPlanner.plan(List.of(
                need("minecraft:cobblestone", 10, 0, 0),  // MISSING
                need(STONE, 10, 10, 0)),                 // ALREADY_PLACED
                false, false, false);

        assertEquals(MaterialResolution.ALREADY_PLACED, plan.needs().get(0).resolution(),
                "satisfied materials are listed before blocking ones");
        assertEquals(MaterialResolution.MISSING, plan.needs().get(1).resolution());
    }

    @Test
    void aCreativePlanListsExactlyWhatMustBeHandedOver() {
        MaterialPlan plan = MaterialPlanner.plan(List.of(
                need(STONE, 248, 200, 0),   // 48 outstanding -> Creative
                need(PLANKS, 96, 96, 0)),   // already built -> nothing needed
                true, true, false);

        assertTrue(plan.requiresCreative());
        assertEquals(1, plan.toAcquireCreatively().size(),
                "only the genuinely short material is taken from Creative");
        assertEquals(48, plan.toAcquireCreatively().get(0).shortfall());
    }

    @Test
    void creativeIsNotRequiredWhenNothingIsShort() {
        MaterialPlan plan = MaterialPlanner.plan(
                List.of(need(STONE, 248, 248, 0)), false, false, false);

        assertFalse(plan.requiresCreative());
        assertTrue(plan.toAcquireCreatively().isEmpty());
    }
}

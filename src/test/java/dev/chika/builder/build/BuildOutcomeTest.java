package dev.chika.builder.build;

import dev.chika.builder.build.material.ItemAmount;
import dev.chika.builder.build.material.MaterialNeed;
import dev.chika.builder.build.material.MaterialPlan;
import dev.chika.builder.build.material.MaterialResolution;
import dev.chika.builder.build.shop.PurchaseReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the exact wording of the player-facing pause report.
 *
 * <p>The specification is specific about what the player sees, and this text is
 * what they act on, so it is pinned down rather than left to drift.
 */
class BuildOutcomeTest {

    @Test
    void pauseReportMatchesTheSpecifiedLayout() {
        MaterialPlan plan = new MaterialPlan(List.of(
                need("minecraft:stone", "Stone", 248, 0, 0),
                need("minecraft:oak_planks", "Oak Planks", 96, 0, 0)), false);

        PurchaseReport purchases = new PurchaseReport(List.of(), List.of(
                new PurchaseReport.PurchaseFailure(
                        new dev.chika.builder.build.shop.ItemRequest(
                                "minecraft:stone", "Stone", 248),
                        "Insufficient money / item unavailable in shop.")));

        BuildOutcome outcome = BuildOutcome.paused(plan, purchases, List.of(
                "Stone x248", "Oak Planks x96"),
                "Insufficient money / item unavailable in shop.");

        List<String> lines = outcome.lines();

        assertEquals("Build paused - missing materials.", lines.get(0));
        assertEquals("Missing:", lines.get(1));
        assertEquals("  Stone x248", lines.get(2));
        assertEquals("  Oak Planks x96", lines.get(3));
        assertEquals("Reason:", lines.get(4));
        assertEquals("  Insufficient money / item unavailable in shop.", lines.get(5));

        assertTrue(outcome.isPaused());
        assertFalse(outcome.isStarted());
    }

    @Test
    void pauseReportTellsThePlayerHowToResume() {
        BuildOutcome outcome = BuildOutcome.paused(
                new MaterialPlan(List.of(), false), PurchaseReport.nothingToDo(),
                List.of(), "reason");

        assertTrue(outcome.lines().stream().anyMatch(line -> line.contains("#chika_build")),
                "the player must be told which command resumes the build");
    }

    @Test
    void aStartedBuildReportsTheFileAndOrigin() {
        BuildOutcome outcome = BuildOutcome.started(
                new MaterialPlan(List.of(), true), PurchaseReport.nothingToDo(),
                "Building 'castle.schematic' from (10, 64, -20).");

        assertTrue(outcome.isStarted());
        assertEquals(BuildStatus.STARTED, outcome.status());
        assertEquals(1, outcome.lines().size());
        assertTrue(outcome.lines().get(0).contains("castle.schematic"));
    }

    @Test
    void aRejectedBuildIsNotReportedAsPaused() {
        BuildOutcome outcome = BuildOutcome.rejected("no such schematic");

        assertEquals(BuildStatus.REJECTED, outcome.status());
        assertFalse(outcome.isPaused(), "a bad filename is not a materials pause");
        assertFalse(outcome.isStarted());
    }

    private static MaterialNeed need(String id, String label, int required,
                                    int placed, int held) {
        return new MaterialNeed(new ItemAmount(id, label, required), required, placed, held,
                MaterialResolution.MISSING);
    }
}
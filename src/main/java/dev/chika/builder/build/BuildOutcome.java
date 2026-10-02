package dev.chika.builder.build;

import dev.chika.builder.build.material.CreativeReport;
import dev.chika.builder.build.material.MaterialPlan;
import dev.chika.builder.build.shop.PurchaseReport;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a build request: whether it started, and everything the player needs
 * to read in chat.
 *
 * <p>The {@code lines} list is exactly what gets sent to the player, formatted
 * here rather than in the command so the reporting is testable.
 */
public record BuildOutcome(BuildStatus status, MaterialPlan plan, PurchaseReport purchases,
                           CreativeReport creative, List<String> lines) {

    public BuildOutcome {
        lines = List.copyOf(lines);
    }

    /** True only when the build is actually running. */
    public boolean isStarted() {
        return this.status == BuildStatus.STARTED;
    }

    /** True when the build was held back and can be resumed later. */
    public boolean isPaused() {
        return this.status == BuildStatus.PAUSED;
    }

    /**
     * Builds the "missing materials" report.
     *
     * <p>Lists the outstanding amount (not the whole schematic requirement) so
     * the player sees what is genuinely still needed.
     */
    public static BuildOutcome paused(MaterialPlan plan, PurchaseReport purchases,
                                      CreativeReport creative, List<String> missing, String reason) {

        List<String> lines = new ArrayList<>();
        lines.add("Build paused - missing materials.");
        lines.add("Missing:");

        if (missing.isEmpty()) {
            lines.add("  (nothing listed)");
        } else {
            for (String need : missing) {
                lines.add("  " + need);
            }
        }

        lines.add("Reason:");
        lines.add("  " + reason);

        if (purchases != null) {
            for (String failure : purchases.describeFailures()) {
                lines.add("  " + failure);
            }
        }

        if (creative != null) {
            for (String failure : creative.describeFailures()) {
                lines.add("  " + failure);
            }
        }

        lines.add("Obtain the items, then run #chika_build again to resume.");

        return new BuildOutcome(BuildStatus.PAUSED, plan, purchases, creative, lines);
    }

    /** Builds a successful start report. */
    public static BuildOutcome started(MaterialPlan plan, PurchaseReport purchases,
                                       CreativeReport creative, String summary) {
        return new BuildOutcome(BuildStatus.STARTED, plan, purchases, creative, List.of(summary));
    }

    /** Builds a rejection report (bad schematic name, etc). */
    public static BuildOutcome rejected(String reason) {
        return new BuildOutcome(BuildStatus.REJECTED, null, null, null, List.of(reason));
    }
}

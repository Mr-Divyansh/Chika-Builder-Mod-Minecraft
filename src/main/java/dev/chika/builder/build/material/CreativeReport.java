package dev.chika.builder.build.material;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of trying to take every {@code CREATIVE} material from Creative mode.
 *
 * <p>Mirrors {@code PurchaseReport}: a rung of the supply ladder reports exactly
 * what it achieved and exactly what it could not, so the caller can pause
 * honestly instead of starting a build with blocks the player does not have.
 *
 * @param granted  items that really reached the inventory
 * @param failures items Creative could not supply, each with a player-facing
 *                 reason
 */
public record CreativeReport(List<CreativeGrant> granted, List<CreativeFailure> failures) {

    public CreativeReport {
        granted = List.copyOf(granted);
        failures = List.copyOf(failures);
    }

    /** Nothing needed taking from Creative. */
    public static CreativeReport nothingToDo() {
        return new CreativeReport(List.of(), List.of());
    }

    /** True when every item was supplied in full. */
    public boolean allSucceeded() {
        return this.failures.isEmpty();
    }

    /** True when the build may continue past this rung. */
    public boolean canContinue() {
        return allSucceeded();
    }

    /** Lines such as {@code "Stone x248 - ..."} for the chat report. */
    public List<String> describeFailures() {
        List<String> lines = new ArrayList<>(this.failures.size());
        for (CreativeFailure failure : this.failures) {
            lines.add(failure.describe());
        }
        return lines;
    }

    /**
     * One successful (possibly partial) hand-over.
     *
     * @param itemId     resource id of the item
     * @param displayName player-facing label
     * @param requested  how many were wanted
     * @param acquired   how many actually reached the inventory
     */
    public record CreativeGrant(String itemId, String displayName, int requested, int acquired) {

        /** {@code "Stone x248"} using the requested amount. */
        public String describe() {
            return this.displayName + " x" + this.requested;
        }
    }

    /**
     * One item Creative could not supply.
     *
     * @param itemId      resource id of the item
     * @param displayName player-facing label
     * @param requested   how many were wanted
     * @param reason      player-facing explanation
     */
    public record CreativeFailure(String itemId, String displayName, int requested, String reason) {

        public String describe() {
            return this.displayName + " x" + this.requested + " - " + this.reason;
        }
    }
}
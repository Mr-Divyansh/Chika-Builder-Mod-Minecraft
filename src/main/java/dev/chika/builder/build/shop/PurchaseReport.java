package dev.chika.builder.build.shop;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of trying to obtain every material a plan marked as {@code SHOP}.
 *
 * @param purchased successfully bought and verified items
 * @param failures  items that could not be bought, each with a player-facing
 *                  reason (unavailable, insufficient money, purchase rejected)
 */
public record PurchaseReport(List<PurchaseResult> purchased, List<PurchaseFailure> failures) {

    public PurchaseReport {
        purchased = List.copyOf(purchased);
        failures = List.copyOf(failures);
    }

    /** Nothing needed buying. */
    public static PurchaseReport nothingToDo() {
        return new PurchaseReport(List.of(), List.of());
    }

    /** True when every attempted purchase succeeded. */
    public boolean allSucceeded() {
        return this.failures.isEmpty();
    }

    /** True when the build may continue. */
    public boolean canContinue() {
        return allSucceeded();
    }

    /** Lines such as {@code "Stone x248 - Insufficient money"} for the chat report. */
    public List<String> describeFailures() {
        List<String> lines = new ArrayList<>(this.failures.size());
        for (PurchaseFailure failure : this.failures) {
            lines.add(failure.describe());
        }
        return lines;
    }

    /**
     * One failed purchase.
     *
     * @param request what was being bought
     * @param reason  player-facing explanation
     */
    public record PurchaseFailure(ItemRequest request, String reason) {

        public String describe() {
            return request.describe() + " - " + reason;
        }
    }
}

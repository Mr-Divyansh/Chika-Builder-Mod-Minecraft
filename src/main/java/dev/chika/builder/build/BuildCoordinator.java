package dev.chika.builder.build;

import dev.chika.builder.build.material.MaterialNeed;
import dev.chika.builder.build.material.MaterialPlan;
import dev.chika.builder.build.material.MaterialPlanner;
import dev.chika.builder.build.material.MaterialResolution;
import dev.chika.builder.build.material.PlayerContext;
import dev.chika.builder.build.material.SchematicAnalyzer;
import dev.chika.builder.build.shop.PurchaseOrchestrator;
import dev.chika.builder.build.shop.PurchaseReport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the full {@code #chika_build} flow and enforces the supply rules.
 *
 * <p>The sequence is deliberately fixed:
 * <pre>
 *   read schematic -> count required blocks -> subtract blocks already correct
 *   -> subtract inventory -> Creative (only if really in Creative)
 *   -> auto-shop (only if shop=true) -> otherwise pause
 * </pre>
 *
 * <p>All collaborators are injected, so the whole coordinator - including the
 * pause and resume decisions - is unit testable without a running game.
 */
public final class BuildCoordinator {

    private final BuildService buildService;
    private final SchematicAnalyzer analyzer;
    private final PlayerContext player;
    private final PurchaseOrchestrator purchases;
    private final Settings settings;

    /** Which optional supply methods are switched on, persisted in the config. */
    public interface Settings {

        boolean isCreativeEnabled();

        boolean isShopEnabled();
    }

    public BuildCoordinator(BuildService buildService, SchematicAnalyzer analyzer,
                            PlayerContext player, PurchaseOrchestrator purchases,
                            Settings settings) {
        this.buildService = buildService;
        this.analyzer = analyzer;
        this.player = player;
        this.purchases = purchases;
        this.settings = settings;
    }

    /**
     * Requests a build of {@code schematic} anchored at {@code origin}.
     *
     * <p>Never throws for a missing material: an unobtainable build is reported
     * as {@link BuildStatus#PAUSED} with the reason, so the player can supply
     * the items and run the same command again to resume.
     */
    public BuildOutcome requestBuild(File schematic, BuildService.Origin origin) {

        // 1. Read the schematic and work out what it needs.
        List<MaterialNeed> required;
        try {
            required = this.analyzer.analyze(schematic, origin);
        } catch (SchematicAnalyzer.SchematicAnalysisException e) {
            return BuildOutcome.rejected("Could not read '" + schematic.getName() + "': " + e.getMessage());
        }

        // 2. Overlay the player's live inventory and apply the priority ladder.
        MaterialPlan plan = planWith(required);

        PurchaseReport report = PurchaseReport.nothingToDo();

        if (!plan.canProceed()) {
            String creativeNote = creativeMisconfiguration();

            // 3. Try the shop for whatever is still short (only when shop=true).
            report = this.purchases.fulfil(plan);

            if (!report.canContinue()) {
                // 4. Purchase failed, or nothing could supply the blocks: pause.
                return BuildOutcome.paused(plan, report, describeMissing(plan),
                        reasonFor(report, creativeNote));
            }

            // 5. Purchases reported success - re-plan from the live inventory so
            //    the build only starts once the items are confirmed present.
            plan = planWith(required);

            if (!plan.canProceed()) {
                return BuildOutcome.paused(plan, report, describeMissing(plan),
                        reasonFor(report, creativeNote));
            }
        }

        return start(schematic, origin, plan, report);
    }

    /**
     * Plans the build against the player's <em>current</em> inventory.
     *
     * <p>The analyzer reports what the schematic needs and how much is already
     * built; holdings are always read live, so a resumed build immediately sees
     * the items the player gathered after the pause.
     */
    private MaterialPlan planWith(List<MaterialNeed> required) {
        List<MaterialNeed> withInventory = new ArrayList<>(required.size());

        for (MaterialNeed need : required) {
            int held = Math.min(this.player.countItem(need.item().itemId()), need.outstanding());
            withInventory.add(new MaterialNeed(need.item(), need.required(), need.alreadyPlaced(),
                    held, need.resolution()));
        }

        return MaterialPlanner.plan(withInventory,
                this.settings.isCreativeEnabled(),
                this.player.isActuallyInCreative(),
                this.settings.isShopEnabled());
    }

    /** Starts (or resumes) the build through the backend. */
    private BuildOutcome start(File schematic, BuildService.Origin origin, MaterialPlan plan,
                               PurchaseReport report) {
        try {
            this.buildService.startBuild(schematic, origin);
        } catch (BuildException e) {
            return BuildOutcome.rejected(e.getMessage());
        }

        String summary = "Building '" + schematic.getName() + "' from " + origin + ".";
        if (!report.purchased().isEmpty()) {
            summary = summary + " Purchased " + report.purchased().size()
                    + " missing material type(s).";
        }

        return BuildOutcome.started(plan, report, summary);
    }

    /**
     * The outstanding amount for everything still unresolved.
     *
     * <p>Uses {@code outstanding()} rather than the raw requirement, so blocks
     * already placed and items already held are never counted as missing.
     */
    private static List<String> describeMissing(MaterialPlan plan) {
        List<String> lines = new ArrayList<>();

        for (MaterialNeed need : plan.needs()) {
            // ALREADY_PLACED and INVENTORY need nothing, so they are not listed.
            // MISSING means no method could cover it.
            // SHOP means we tried to buy it and could not.
            if (need.resolution() == MaterialResolution.MISSING
                    || need.resolution() == MaterialResolution.SHOP) {
                lines.add(need.describeOutstanding());
            }
        }

        return lines;
    }

    /** The most useful single explanation for why the build stopped. */
    private static String reasonFor(PurchaseReport report, String creativeNote) {
        if (!report.failures().isEmpty()) {
            return report.failures().get(0).reason();
        }
        if (creativeNote != null) {
            return creativeNote;
        }
        return "Not enough materials in the inventory.";
    }

    /**
     * Explains a Creative mismatch, or {@code null} when there is none.
     *
     * <p>Chika Builder never changes the player's gamemode. It only reads it,
     * and when Creative is enabled but the player is not actually in Creative
     * the situation is reported instead of being worked around or faked.
     */
    private String creativeMisconfiguration() {
        if (this.settings.isCreativeEnabled() && !this.player.isActuallyInCreative()) {
            return "Creative mode is required (you are not in Creative), "
                    + "and no other source can supply the missing blocks.";
        }
        return null;
    }
}


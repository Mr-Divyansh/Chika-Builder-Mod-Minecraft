package dev.chika.builder.build;

import dev.chika.builder.build.material.CreativeAcquisition;
import dev.chika.builder.build.material.CreativeReport;
import dev.chika.builder.build.material.CreativeSupplier;
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
 * <p>Both optional rungs are <b>verified</b>: finishing a Creative hand-over or a
 * purchase is not enough, because the re-plan step below re-reads the live
 * inventory and the build only starts once the blocks are genuinely present.
 *
 * <p>All collaborators are injected, so the whole coordinator - including the
 * pause and resume decisions - is unit testable without a running game.
 */
public final class BuildCoordinator {

    private final BuildService buildService;
    private final SchematicAnalyzer analyzer;
    private final PlayerContext player;
    private final PurchaseOrchestrator purchases;
    private final CreativeAcquisition creative;
    private final Settings settings;

    /** Which optional supply methods are switched on, persisted in the config. */
    public interface Settings {

        boolean isCreativeEnabled();

        boolean isShopEnabled();
    }

    public BuildCoordinator(BuildService buildService, SchematicAnalyzer analyzer,
                            PlayerContext player, PurchaseOrchestrator purchases,
                            CreativeSupplier creativeSupplier, Settings settings) {
        this.buildService = buildService;
        this.analyzer = analyzer;
        this.player = player;
        this.purchases = purchases;
        // Creative blocks are read back through the same live inventory the plan
        // uses, so a hand-over is only ever believed once the items are there.
        this.creative = new CreativeAcquisition(creativeSupplier, player::countItem);
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
        CreativeReport creativeReport = CreativeReport.nothingToDo();

        if (!plan.canProceed()) {
            // 3. Hand over whatever Creative must supply (only when creative=true
            //    AND the player is genuinely in Creative).
            if (plan.requiresCreative()) {
                creativeReport = this.creative.acquire(plan);

                // Re-plan from the live inventory so a partial hand-over (a full
                // inventory, say) leaves a correct picture of what is still short.
                plan = planWith(required);

                if (!plan.canProceed()) {
                    return BuildOutcome.paused(plan, report, creativeReport,
                            describeMissing(plan), creativeReason(creativeReport));
                }
            }

            // 4. Try the shop for whatever is still short (only when shop=true).
            report = this.purchases.fulfil(plan);

            if (!report.canContinue()) {
                // 5. Purchase failed, or nothing could supply the blocks: pause.
                return BuildOutcome.paused(plan, report, creativeReport,
                        describeMissing(plan), reasonFor(report, creativeReport));
            }

            // 6. Purchases reported success - re-plan from the live inventory so
            //    the build only starts once the items are confirmed present.
            plan = planWith(required);

            if (!plan.canProceed()) {
                return BuildOutcome.paused(plan, report, creativeReport,
                        describeMissing(plan), reasonFor(report, creativeReport));
            }
        }

        return start(schematic, origin, plan, report, creativeReport);
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
                               PurchaseReport report, CreativeReport creativeReport) {
        try {
            this.buildService.startBuild(schematic, origin);
        } catch (BuildException e) {
            return BuildOutcome.rejected(e.getMessage());
        }

        String summary = "Building '" + schematic.getName() + "' from " + origin + ".";
        if (!creativeReport.granted().isEmpty()) {
            summary = summary + " Supplied " + creativeReport.granted().size()
                    + " material type(s) from Creative.";
        }
        if (!report.purchased().isEmpty()) {
            summary = summary + " Purchased " + report.purchased().size()
                    + " missing material type(s).";
        }

        return BuildOutcome.started(plan, report, creativeReport, summary);
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
            // CREATIVE means the hand-over did not arrive (checked by the caller,
            // which reports the Creative reason alongside this list).
            if (need.resolution() == MaterialResolution.MISSING
                    || need.resolution() == MaterialResolution.SHOP) {
                lines.add(need.describeOutstanding());
            }
        }

        return lines;
    }

    /** The most useful single explanation for why the build stopped. */
    private String reasonFor(PurchaseReport report, CreativeReport creativeReport) {
        if (!report.failures().isEmpty()) {
            return report.failures().get(0).reason();
        }
        String creativeNote = creativeReason(creativeReport);
        if (creativeNote != null) {
            return creativeNote;
        }
        String mismatch = creativeMisconfiguration();
        if (mismatch != null) {
            return mismatch;
        }
        return "Not enough materials in the inventory.";
    }

    /**
     * Explains a Creative shortfall, or {@code null} when Creative is not the
     * reason the build stopped.
     *
     * <p>Chika Builder never changes the player's gamemode. Creative is only
     * used because the player <em>is</em> in Creative, so a failure here is
     * reported (inventory full, hand-over not confirmed) rather than worked
     * around or faked.
     */
    private static String creativeReason(CreativeReport creativeReport) {
        if (creativeReport.failures().isEmpty()) {
            return null;
        }
        return creativeReport.failures().get(0).reason();
    }

    /**
     * Explains a Creative setting that cannot be honoured, or {@code null} when
     * there is nothing to explain.
     *
     * <p>Chika Builder never changes the player's gamemode. It only reads it, and
     * when Creative is enabled but the player is not actually in Creative the
     * situation is reported instead of being worked around or faked.
     */
    private String creativeMisconfiguration() {
        if (this.settings.isCreativeEnabled() && !this.player.isActuallyInCreative()) {
            return "Creative mode is required (you are not in Creative), "
                    + "and no other source can supply the missing blocks.";
        }
        return null;
    }
}


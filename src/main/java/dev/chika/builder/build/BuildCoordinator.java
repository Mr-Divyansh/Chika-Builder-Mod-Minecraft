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

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Chika Builder");

    private final BuildService buildService;
    private final SchematicAnalyzer analyzer;
    private final PlayerContext player;
    private final PurchaseOrchestrator purchases;
    private final CreativeAcquisition creative;
    private final Settings settings;

    /** The build currently handed to the engine, so a pause can be recovered. */
    private File activeSchematic;
    private BuildService.Origin activeOrigin;

    /** The most recent Creative hand-over, for player-facing reporting. */
    private CreativeReport lastCreativeReport = CreativeReport.nothingToDo();

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
        logPlan(schematic.getName(), plan);

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

    /**
     * Tries to supply whatever the running build still needs, and reports both
     * how many items genuinely arrived and whether the requirements are now
     * actually satisfied.
     *
     * <p>This is what the supervisor calls when the engine pauses mid-build.
     * The plan is rebuilt from the live world first, so blocks already placed
     * are excluded and the build continues from the remaining work rather than
     * starting over.
     *
     * <p><b>Satisfaction is decided by the inventory, never by the delivery
     * claim.</b> After every hand-over the plan is re-computed from the live
     * counts across <b>all</b> material types, so a partial round (191 of 248,
     * or one material type done while another is still short) reports
     * {@code satisfied=false} and the supervisor must not resume.
     *
     * @return a {@link BuildSupervisor.SupplyResult}; never {@code null}
     */
    public BuildSupervisor.SupplyResult supplyOutstanding() {
        if (this.activeSchematic == null || this.activeOrigin == null) {
            return BuildSupervisor.SupplyResult.none();
        }

        try {
            List<MaterialNeed> required = this.analyzer.analyze(this.activeSchematic, this.activeOrigin);

            if (required == null || required.isEmpty()) {
                return BuildSupervisor.SupplyResult.none();
            }

            MaterialPlan plan = planWith(required);

            if (!plan.requiresCreative()) {
                // Nothing Creative can currently add. Satisfaction still comes
                // from the live plan: the player may have gathered the blocks
                // by hand while the engine waited.
                return new BuildSupervisor.SupplyResult(0, plan.canProceed());
            }

            CreativeReport report = this.creative.acquire(plan);

            int delivered = 0;

            for (CreativeReport.CreativeGrant grant : report.granted()) {
                delivered += grant.acquired();
            }

            // Re-plan from the LIVE inventory: whether the engine may resume is
            // decided by what is genuinely in storage now - every material
            // type - never by the delivery claim itself.
            plan = planWith(required);
            boolean satisfied = plan.canProceed();

            this.lastCreativeReport = report;
            LOGGER.info("[Chika Builder] Mid-build supply round for '{}': "
                            + "{} item(s) delivered, {} failure(s), requirements satisfied={}{}.",
                    this.activeSchematic.getName(), delivered, report.failures().size(), satisfied,
                    satisfied ? "" : ", still short: " + String.join("; ", shortfallLines(plan)));
            logRemainingRequirements(plan);

            return new BuildSupervisor.SupplyResult(delivered, satisfied);
        } catch (SchematicAnalyzer.SchematicAnalysisException e) {
            // The schematic cannot be re-read, so progress cannot be trusted.
            return BuildSupervisor.SupplyResult.none();
        }
    }

    /**
     * The exact materials still short of their outstanding requirement, each
     * like {@code "Dirt x57"}, read from a live re-plan.
     *
     * <p>Used for the honest {@code Missing: ...} report when supply could not
     * close the gap: it is the inventory speaking, not a delivery claim.
     */
    /**
     * A snapshot of the material state, for the {@code #chika_builder debug}
     * command. Diagnostics only: it never starts, resumes or stops a build.
     *
     * @param hasBuild           whether a build is currently handed to the engine
     * @param creativeOn         the {@code #chika_builder creative} setting
     * @param actuallyInCreative the player's real gamemode
     * @param occupiedSlots      how many of the 36 storage slots are in use
     * @param required           distinct material types the build needs
     * @param present            of those, how many the inventory already covers
     * @param missingLines       one line per material that is still missing
     */
    public record Diagnostics(boolean hasBuild, boolean creativeOn, boolean actuallyInCreative,
                              int occupiedSlots, int required, int present,
                              List<String> missingLines) {

        /** How many materials are still missing. */
        public int missing() {
            return this.missingLines.size();
        }
    }

    /**
     * Builds the diagnostic snapshot for the running build.
     *
     * <p>Everything is measured, never assumed: the gamemode is read from the
     * client, the slot count from the player's real storage, and the materials
     * from a fresh re-plan of the active schematic against that same inventory.
     */
    public Diagnostics diagnostics() {
        boolean creativeOn = this.settings.isCreativeEnabled();
        boolean inCreative = this.player.isActuallyInCreative();

        if (this.activeSchematic == null || this.activeOrigin == null) {
            return new Diagnostics(false, creativeOn, inCreative, this.player.occupiedSlots(),
                    0, 0, List.of());
        }

        try {
            List<MaterialNeed> required =
                    this.analyzer.analyze(this.activeSchematic, this.activeOrigin);
            MaterialPlan plan = planWith(required);
            List<String> missing = new ArrayList<>();
            int present = 0;

            for (MaterialNeed need : plan.needs()) {
                if (need.outstanding() == 0 || need.isSatisfied()) {
                    present++;
                } else {
                    missing.add(need.item().itemId() + " " + need.item().displayName()
                            + " x" + need.outstanding());
                }
            }

            return new Diagnostics(true, creativeOn, inCreative, this.player.occupiedSlots(),
                    plan.needs().size(), present, missing);
        } catch (SchematicAnalyzer.SchematicAnalysisException e) {
            return new Diagnostics(true, creativeOn, inCreative, this.player.occupiedSlots(),
                    0, 0, List.of("(schematic could not be re-read: " + e.getMessage() + ")"));
        }
    }

    /**
     * The exact materials the build still does not have, one {@code "Name xN"}
     * line each.
     *
     * <p>Used for the honest {@code Missing: ...} report when supply could not
     * close the gap: it is the inventory speaking, not a delivery claim.
     */
    public List<String> remainingShortfalls() {
        if (this.activeSchematic == null || this.activeOrigin == null) {
            return List.of();
        }

        try {
            List<MaterialNeed> required = this.analyzer.analyze(this.activeSchematic, this.activeOrigin);

            if (required == null || required.isEmpty()) {
                return List.of();
            }

            return shortfallLines(planWith(required));
        } catch (SchematicAnalyzer.SchematicAnalysisException e) {
            return List.of();
        }
    }

    /**
     * One {@code "Name xN"} line per material the build still does not have.
     *
     * <p>Satisfaction, not a raw count: with Creative a material the player
     * holds any of is satisfied, so it is not listed as missing. Only materials
     * that no rung has covered yet are reported, which is exactly what the
     * engine is still unable to place.
     */
    private static List<String> shortfallLines(MaterialPlan plan) {
        List<String> lines = new ArrayList<>();

        for (MaterialNeed need : plan.needs()) {
            if (!need.isSatisfied() && need.outstanding() > 0) {
                lines.add(need.item().displayName() + " x" + need.outstanding());
            }
        }

        return lines;
    }

    /**
     * Logs exactly which materials are still outstanding, one line each.
     *
     * <p>A bare total is not diagnostic: a live stall has to name the blocks
     * responsible, with their ids, numbers and how the planner resolved them.
     */
    private void logRemainingRequirements(MaterialPlan plan) {
        boolean any = false;

        for (MaterialNeed need : plan.needs()) {
            if (need.isSatisfied() || need.outstanding() <= 0) {
                continue;
            }

            any = true;
            LOGGER.info("[Chika Builder] Remaining requirements: material={} name={} "
                            + "outstanding={} held={} resolution={}",
                    need.item().itemId(), need.item().displayName(), need.outstanding(),
                    need.inInventory(), need.resolution());
        }

        if (!any) {
            LOGGER.info("[Chika Builder] Remaining requirements: none");
        }
    }

    /**
     * Counts schematic blocks that are not yet correct in the world.
     *
     * <p>Completion is decided by this, never by "the engine stopped", because a
     * build that stalled can leave blocks genuinely unplaced.
     *
     * @return the outstanding count, or {@code -1} when it cannot be determined
     */
    public int outstandingBlocks() {
        if (this.activeSchematic == null || this.activeOrigin == null) {
            return 0;
        }

        try {
            List<MaterialNeed> required = this.analyzer.analyze(this.activeSchematic, this.activeOrigin);
            int outstanding = 0;

            for (MaterialNeed need : required) {
                outstanding += need.outstanding();
            }

            return outstanding;
        } catch (SchematicAnalyzer.SchematicAnalysisException e) {
            return -1;
        }
    }

    /** The most recent Creative hand-over report, for player-facing messaging. */
    public CreativeReport lastCreativeReport() {
        return this.lastCreativeReport;
    }

    /**
     * One log line per material so a live run shows exactly what was required,
     * already placed, held, and which rung will cover it.
     */
    private static void logPlan(String schematicName, MaterialPlan plan) {
        for (MaterialNeed need : plan.needs()) {
            LOGGER.info("[Chika Builder] {} material {}: required={}, already placed={}, "
                            + "held={}, outstanding={}, shortfall={} -> {}",
                    schematicName, need.item().itemId(), need.required(), need.alreadyPlaced(),
                    need.inInventory(), need.outstanding(), need.shortfall(), need.resolution());
        }
    }

    /** Whether a build is currently handed to the engine. */
    public boolean hasActiveBuild() {
        return this.activeSchematic != null;
    }

    /** Forgets the active build once it has finished or been abandoned. */
    public void clearActiveBuild() {
        this.activeSchematic = null;
        this.activeOrigin = null;
        this.lastCreativeReport = null;
    }

    /** Starts (or resumes) the build through the backend. */
    private BuildOutcome start(File schematic, BuildService.Origin origin, MaterialPlan plan,
                               PurchaseReport report, CreativeReport creativeReport) {
        // Final picture at the moment the engine takes over: everything the
        // plan relies on is on screen in the log if the build later stalls.
        logPlan(schematic.getName(), plan);

        try {
            this.buildService.startBuild(schematic, origin);
        } catch (BuildException e) {
            return BuildOutcome.rejected(e.getMessage());
        }

        // Remember the build so a later pause can be supplied and resumed without
        // the player re-typing the command. Progress is always re-derived from
        // the world, so a resume never rebuilds finished work.
        this.activeSchematic = schematic;
        this.activeOrigin = origin;

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


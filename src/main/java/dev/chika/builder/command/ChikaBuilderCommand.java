package dev.chika.builder.command;

import dev.chika.builder.build.BuildCoordinator;
import dev.chika.builder.config.ChikaConfig;
import dev.chika.builder.ui.ChikaChat;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code #chika_builder} — Chika Builder's settings command.
 *
 * <pre>
 *   #chika_builder creative true | false
 *   #chika_builder shop     true | false
 * </pre>
 *
 * <p>Both arguments complete: {@code #chika_builder } suggests {@code creative}
 * and {@code shop}, and {@code #chika_builder creative } suggests {@code true}
 * and {@code false}, filtered by whatever has been typed so far.
 *
 * <p>This is a Chika Builder command, not an engine command: it never enables an
 * engine feature and it is never about pathing. It only flips a persisted
 * setting, so a typo cannot start a build.
 */
public final class ChikaBuilderCommand extends Command {

    /** The exact user-facing name required by the specification. */
    public static final String COMMAND_NAME = "chika_builder";

    private static final String ARG_CREATIVE = "creative";
    private static final String ARG_SHOP = "shop";

    /**
     * A read-only diagnostic that prints the live material state.
     *
     * <p>It never starts, resumes or stops a build, and never changes a
     * setting - it only reports what is actually true right now, which is what
     * a stalled build needs to explain itself.
     */
    private static final String ARG_DEBUG = "debug";

    private static final List<String> OPTIONS = List.of(ARG_CREATIVE, ARG_SHOP);

    /**
     * The two values {@code creative} and {@code shop} accept.
     *
     * <p>These are exactly the spellings {@link #execute} accepts. Keeping the
     * two in step is what makes Tab completion trustworthy: it can only ever
     * offer a value the command will actually take.
     */
    private static final List<String> VALUES = List.of("true", "false");

    private final BuildCoordinator coordinator;

    /**
     * One-line movement-watchdog status for {@code #chika_builder debug}.
     *
     * <p>Supplied by the client, and {@code null} when no watchdog is wired (for
     * example in tests). Debug output must never fail because a diagnostic is
     * missing, so it degrades to a plain "unavailable".
     */
    private final java.util.function.Supplier<String> movementStatus;

    public ChikaBuilderCommand(IBaritone engine) {
        this(engine, null);
    }

    public ChikaBuilderCommand(IBaritone engine, BuildCoordinator coordinator) {
        this(engine, coordinator, null);
    }

    public ChikaBuilderCommand(IBaritone engine, BuildCoordinator coordinator,
                               java.util.function.Supplier<String> movementStatus) {
        super(engine, COMMAND_NAME);
        this.coordinator = coordinator;
        this.movementStatus = movementStatus;
    }

    @Override
    public String getShortDesc() {
        return "Chika Builder settings";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "chika_builder.settings.usage",
                "chika_builder.settings.desc"
        );
    }

    @Override
    public void execute(String commandName, IArgConsumer args) {
        if (!args.hasExactly(2)) {
            say("Usage: #" + COMMAND_NAME + " creative|shop true|false");
            return;
        }

        // Read exactly once: IArgConsumer.getString() advances the cursor, so
        // asking again would hand back the *value* instead of the setting name.
        String rawSetting = args.getString();
        String setting = rawSetting.toLowerCase(Locale.ROOT);

        if (ARG_DEBUG.equals(setting)) {
            // Read-only diagnostic: it takes no value and changes nothing.
            reportDebug(ChikaConfig.get());
            return;
        }

        if (!OPTIONS.contains(setting)) {
            say("Unknown setting '" + rawSetting + "'. Use: creative or shop.");
            return;
        }

        String rawValue = args.getString().toLowerCase(Locale.ROOT);

        Boolean value = parseBoolean(rawValue);
        if (value == null) {
            say("Value must be true or false, not '" + rawValue + "'.");
            return;
        }

        ChikaConfig config = ChikaConfig.get();

        if (ARG_CREATIVE.equals(setting)) {
            config.setCreativeEnabled(value);
            say("Creative building " + (value ? "enabled" : "disabled") + ".");
            if (value) {
                say("Note: this never changes your gamemode. You must already be in Creative.");
            }
        } else {
            config.setShopEnabled(value);
            say("Auto-shop " + (value ? "enabled" : "disabled") + ".");
        }
    }

    /**
     * Prints the live material state so a stalled build can be diagnosed
     * without guesswork.
     *
     * <p>Everything reported here is measured: the setting, the player's real
     * gamemode, the occupied storage slots the engine scans, and the material
     * list of the running build. It changes nothing.
     */
    private void reportDebug(ChikaConfig config) {
        if (this.coordinator == null) {
            say("Creative enabled: " + config.isCreativeEnabled());
            say("Material state unavailable: no build coordinator.");
            return;
        }

        BuildCoordinator.Diagnostics diagnostics = this.coordinator.diagnostics();

        say("Creative enabled: " + diagnostics.creativeOn());
        say("Actual gamemode: " + (diagnostics.actuallyInCreative() ? "Creative" : "not Creative"));
        say("Inventory storage slots: " + diagnostics.occupiedSlots() + "/36 occupied");

        if (!diagnostics.hasBuild()) {
            say("No build is running, so there are no required materials to report.");
            return;
        }

        say("Required materials: " + diagnostics.required());
        say("Present materials: " + diagnostics.present());
        say("Missing materials: " + diagnostics.missing());

        for (String line : diagnostics.missingLines()) {
            say("  " + line);
        }

        // The movement watchdog's own view of the build. A stall shows up here
        // as a rising stationaryTicks count, which is what identifies a build
        // that is running but not going anywhere.
        say("Movement watchdog: " + movementStatus());
    }

    /** Never throws: a missing diagnostic must not break {@code debug}. */
    private String movementStatus() {
        if (this.movementStatus == null) {
            return "unavailable";
        }
        try {
            String status = this.movementStatus.get();
            return status == null ? "unavailable" : status;
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /**
     * Suggests the setting name first, then its value.
     *
     * <p>The previous implementation asked {@code args.has(1)} and {@code
     * args.has(2)}. {@code has(n)} means "at least {@code n}" and counts the
     * argument currently being typed, so the ordinary {@code #chika_builder
     * creative <TAB>} input (one argument plus the empty one being completed)
     * satisfied "at least 1" first and only ever offered the setting names.
     * Testing the exact counts fixes that, and reading the filter from
     * {@code peek(0)} for the setting and {@code peek(1)} for the value -
     * the argument under the cursor - rather than always reading the first
     * argument keeps the partial "t"/"f" matching aimed at the value rather
     * than at the setting the player already finished typing.
     */
    @Override
    public Stream<String> tabComplete(String commandName, IArgConsumer args) {
        if (args.hasExactly(1)) {
            return matching(OPTIONS, args.peek(0).getValue());
        }

        if (args.hasExactly(2)) {
            return matching(VALUES, args.peek(1).getValue());
        }

        return Stream.empty();
    }

    /** Every candidate starting with what has been typed for the current argument. */
    private static Stream<String> matching(List<String> candidates, String rawTyped) {
        String typed = rawTyped == null ? "" : rawTyped.toLowerCase(Locale.ROOT);

        return candidates.stream()
                .filter(candidate -> candidate.startsWith(typed))
                .sorted();
    }

    /**
     * Accepts only the two documented spellings.
     *
     * @return the value, or {@code null} when the text is not a boolean
     */
    private static Boolean parseBoolean(String raw) {
        if ("true".equals(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equals(raw)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Sends a message with Chika Builder's own pink prefix.
     *
     * <p>Every user-facing line from this command goes through
     * {@link ChikaChat}, so the branding is consistent and the engine's name can
     * never appear.
     */
    private void say(String message) {
        if (this.ctx.minecraft() != null && this.ctx.minecraft().player != null) {
            this.ctx.minecraft().player.sendSystemMessage(ChikaChat.message(message));
        }
    }
}

package dev.chika.builder.command;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import dev.chika.builder.build.BuildCoordinator;
import dev.chika.builder.build.BuildOutcome;
import dev.chika.builder.build.BuildService;
import dev.chika.builder.schematic.SchematicLocator;
import net.minecraft.core.BlockPos;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code #chika_build <filename>.schematic} — the only build command Chika Builder exposes.
 *
 * <p>Usage: {@code #chika_build house.schematic} builds the named schematic from
 * {@code .minecraft/schematics/} with its corner at the player's feet.
 *
 * <p>The command itself is intentionally thin: it resolves the file, asks the
 * {@link BuildCoordinator} to run the build, and prints the coordinator's report.
 * All supply logic (inventory, Creative, auto-shop, pausing) lives in the
 * coordinator, which is pure and fully unit tested.
 */
public final class ChikaBuildCommand extends Command {

    private final BuildCoordinator coordinator;
    private final SchematicLocator locator;

    public ChikaBuildCommand(IBaritone baritone, BuildCoordinator coordinator,
                             SchematicLocator locator) {
        super(baritone, CommandLockdown.ALLOWED_COMMAND);
        this.coordinator = coordinator;
        this.locator = locator;
    }

    @Override
    public String getShortDesc() {
        return "Build a schematic from .minecraft/schematics/";
    }

    @Override
    public List<String> getLongDesc() {
        // Chika Builder's own translation keys - no engine branding is shown.
        return List.of(
                "chika_builder.command.usage",
                "chika_builder.command.desc"
        );
    }

    @Override
    public void execute(String commandName, IArgConsumer args) {
        if (!args.hasExactlyOne()) {
            throw new IllegalArgumentException("Expected exactly one schematic name.");
        }

        String requested = args.getString();
        File schematic = this.locator.find(requested);

        if (schematic == null) {
            say(this.ctx, "Chika Builder: no schematic named '" + requested
                    + "' in " + this.locator.directory().getName() + ".");
            return;
        }

        BlockPos anchor = this.ctx.playerFeet();
        BuildService.Origin origin =
                new BuildService.Origin(anchor.getX(), anchor.getY(), anchor.getZ());

        try {
            BuildOutcome outcome = this.coordinator.requestBuild(schematic, origin);

            for (String line : outcome.lines()) {
                say(this.ctx, line);
            }
        } catch (Throwable t) {
            say(this.ctx, "Chika Builder: unexpected error - " + t);
        }
    }

    @Override
    public Stream<String> tabComplete(String commandName, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return this.locator.suggest(args.peekString()).stream();
        }
        return Stream.empty();
    }

    /** Sends a message to the player, free of any engine command chatter. */
    private static void say(baritone.api.utils.IPlayerContext ctx, String message) {
        if (ctx.minecraft() != null && ctx.minecraft().player != null) {
            ctx.minecraft().player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(message));
        }
    }

    /** Exposed for tests / diagnostics. */
    public List<String> availableSchematics() {
        return this.locator.list().stream().collect(Collectors.toList());
    }
}

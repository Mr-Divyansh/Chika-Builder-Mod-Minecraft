package dev.chika.builder.command;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import dev.chika.builder.build.BuildException;
import dev.chika.builder.build.BuildService;
import dev.chika.builder.schematic.SchematicLocator;
import net.minecraft.core.BlockPos;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code #chika_build <filename>.schematic} — the only command Chika Builder exposes.
 *
 * <p>Usage: {@code #chika_build house.schematic} builds the named schematic from
 * {@code .minecraft/schematics/} with its corner at the player's feet.
 */
public final class ChikaBuildCommand extends Command {

    private final BuildService buildService;
    private final SchematicLocator locator;

    public ChikaBuildCommand(IBaritone baritone, BuildService buildService, SchematicLocator locator) {
        super(baritone, CommandLockdown.ALLOWED_COMMAND);
        this.buildService = buildService;
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

        if (!this.buildService.isAvailable()) {
            say(this.ctx, "Chika Builder: build backend is not ready yet. Try again in a moment.");
            return;
        }

        BlockPos anchor = this.ctx.playerFeet();
        BuildService.Origin origin =
                new BuildService.Origin(anchor.getX(), anchor.getY(), anchor.getZ());

        try {
            this.buildService.startBuild(schematic, origin);
            // The backend name is intentionally not shown: it is an internal
            // implementation detail and should never surface to the player.
            say(this.ctx, "Chika Builder: building '" + schematic.getName()
                    + "' from " + origin + ".");
        } catch (BuildException e) {
            say(this.ctx, "Chika Builder: " + e.getMessage());
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
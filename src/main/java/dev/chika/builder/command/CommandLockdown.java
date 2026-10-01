package dev.chika.builder.command;

import baritone.api.BaritoneAPI;
import baritone.api.command.ICommand;
import baritone.api.command.registry.Registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Restricts the engine's command surface down to Chika Builder's single command.
 *
 * <p>The engine registers a large command set of its own (navigation, mining,
 * following, exploration, and more) via a mixin on the client's command
 * suggestion helper. Chika Builder exposes exactly one command,
 * {@code #chika_build}, so this class walks the engine's command registry and
 * <strong>unregisters everything that is not ours</strong>.
 *
 * <p>This is real removal, not hiding: an unregistered command cannot be
 * executed <em>and</em> disappears from tab completion, so there is no way for
 * the player to reach it. The movement / pathing / placement code that the
 * builder depends on is untouched — only the command entry points are removed.
 */
public final class CommandLockdown {

    /**
     * The one and only command Chika Builder exposes.
     */
    public static final String ALLOWED_COMMAND = "chika_build";

    /**
     * Every command name that must never be reachable.
     *
     * <p>These are removed unconditionally and are additionally asserted to be
     * absent by the test suite. Matching is case-insensitive and covers aliases,
     * so a renamed or aliased variant is still caught.
     */
    private static final List<String> FORBIDDEN_COMMANDS = List.of(
            "goto",
            "follow",
            "mine",
            "stop",
            "come",
            "explore",
            "build",
            "path",
            "goal",
            "farm",
            "pickup",
            "tunnel",
            "elytra",
            "find",
            "blacklist",
            "click",
            "thisway",
            "come",
            "surface",
            "proc",
            "rep",
            "repack",
            "sel",
            "set",
            "waypoints",
            "version",
            "help",
            "gc",
            "invert",
            "eta",
            "axis",
            "render",
            "litematica",
            "schematica",
            "reloadall",
            "saveall"
    );

    private CommandLockdown() {
    }

    /**
     * Unregisters every command except {@link #ALLOWED_COMMAND}.
     *
     * <p>Safe to call repeatedly: commands already removed simply are not found
     * again. Called repeatedly because the engine finishes registering its
     * commands after mod init.
     *
     * @return the names of the commands removed by this call
     */
    public static List<String> enforce() {
        List<String> removed = new ArrayList<>();

        Registry<ICommand> registry;
        try {
            registry = BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().getRegistry();
        } catch (Throwable t) {
            // Engine not ready yet; the caller retries.
            return removed;
        }

        // Registry exposes a public `entries` collection (it is not Iterable).
        List<ICommand> doomed = new ArrayList<>();
        for (ICommand command : registry.entries) {
            if (command == null || isAllowed(command)) {
                continue;
            }
            doomed.add(command);
        }

        for (ICommand command : doomed) {
            List<String> names = safeNames(command);
            try {
                registry.unregister(command);
                removed.add(String.join("/", names));
            } catch (Throwable ignored) {
                // If one command refuses to unregister, keep going with the rest.
            }
        }

        return removed;
    }

    /**
     * True when the registry currently exposes any command other than
     * {@code chika_build}. Used by the test suite and by the startup self-check.
     */
    public static List<String> findLeakedCommands() {
        List<String> leaked = new ArrayList<>();

        Registry<ICommand> registry;
        try {
            registry = BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().getRegistry();
        } catch (Throwable t) {
            return leaked;
        }

        for (ICommand command : registry.entries) {
            if (command != null && !isAllowed(command)) {
                leaked.addAll(safeNames(command));
            }
        }

        return leaked;
    }

    /** The command names that must never be reachable. Exposed for tests/docs. */
    public static List<String> forbiddenCommands() {
        return FORBIDDEN_COMMANDS;
    }

    /**
     * Only Chika Builder's own command survives. No other command is allowed,
     * including execution helpers such as cancel/pause/resume.
     */
    private static boolean isAllowed(ICommand command) {
        for (String name : safeNames(command)) {
            if (ALLOWED_COMMAND.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> safeNames(ICommand command) {
        try {
            List<String> names = command.getNames();
            return names == null ? List.of() : names;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** True when the given name is on the forbidden list. Exposed for tests. */
    public static boolean isForbidden(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (ALLOWED_COMMAND.equals(lower)) {
            return false;
        }
        return FORBIDDEN_COMMANDS.contains(lower);
    }
}
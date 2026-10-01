package dev.chika.builder.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that Chika Builder exposes only its own two commands and that every
 * engine command is unreachable.
 */
class CommandLockdownTest {

    @Test
    void onlyAllowedCommandIsChikaBuild() {
        assertEquals("chika_build", CommandLockdown.ALLOWED_COMMAND);
    }

    @Test
    void settingsCommandUsesTheExactNameFromTheSpec() {
        // #chika_builder creative|shop true|false - spelling is part of the API.
        assertEquals("chika_builder", ChikaBuilderCommand.COMMAND_NAME);
        assertTrue(CommandLockdown.isAllowedName(ChikaBuilderCommand.COMMAND_NAME));
    }

    @Test
    void chikaBuilderExposesExactlyTwoCommands() {
        // #chika_build (build) and #chika_builder (settings). No engine command
        // is ever on this list.
        assertEquals(List.of("chika_build", "chika_builder"), CommandLockdown.allowedCommands());
        assertEquals("chika_builder", CommandLockdown.ALLOWED_SETTINGS_COMMAND);
    }

    @Test
    void bothChikaCommandsAreAllowedAndNotForbidden() {
        assertFalse(CommandLockdown.isForbidden("chika_build"));
        assertFalse(CommandLockdown.isForbidden("chika_builder"),
                "the settings command must be reachable");

        assertTrue(CommandLockdown.isAllowedName("chika_build"));
        assertTrue(CommandLockdown.isAllowedName("chika_builder"));
    }

    @Test
    void theSpecificCommandsTheUserCalledOutAreForbidden() {
        // These must never be executable through Chika Builder.
        for (String command : List.of(
                "goto", "follow", "mine", "stop", "come", "explore",
                "build", "path", "goal")) {
            assertTrue(CommandLockdown.isForbidden(command),
                    command + " must be forbidden");
        }
    }

    @Test
    void chikaBuildItselfIsNotForbidden() {
        assertFalse(CommandLockdown.isForbidden("chika_build"),
                "the one allowed command must not be on the forbidden list");
    }

    @Test
    void executionHelpersAreAlsoNotAllowed() {
        // Chika Builder exposes ONLY #chika_build, so the engine's own
        // execution helpers must be disabled too.
        for (String command : List.of("cancel", "pause", "resume", "forcecancel")) {
            assertFalse("chika_build".equals(command), "sanity");
            assertTrue(isNotTheAllowedCommand(command),
                    command + " must not be reachable");
        }
    }

    @Test
    void forbiddenListIsNonEmptyAndLowercase() {
        List<String> forbidden = CommandLockdown.forbiddenCommands();
        assertFalse(forbidden.isEmpty(), "forbidden list must not be empty");
        for (String name : forbidden) {
            assertEquals(name.toLowerCase(java.util.Locale.ROOT), name,
                    "forbidden names must be lowercase: " + name);
        }
    }

    private static boolean isNotTheAllowedCommand(String name) {
        return !CommandLockdown.ALLOWED_COMMAND.equalsIgnoreCase(name);
    }
}
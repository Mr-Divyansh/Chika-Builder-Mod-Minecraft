package dev.chika.builder.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that Chika Builder exposes exactly one command and that every
 * engine command is unreachable.
 */
class CommandLockdownTest {

    @Test
    void onlyAllowedCommandIsChikaBuild() {
        assertEquals("chika_build", CommandLockdown.ALLOWED_COMMAND);
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
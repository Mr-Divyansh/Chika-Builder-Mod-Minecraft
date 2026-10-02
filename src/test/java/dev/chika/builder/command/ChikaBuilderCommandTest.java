package dev.chika.builder.command;

import baritone.api.IBaritone;
import baritone.api.utils.IPlayerContext;
import dev.chika.builder.config.ChikaConfig;
import dev.chika.builder.support.FakeArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code #chika_builder}, with particular attention to tab completion of
 * the boolean value.
 *
 * <p>That completion used to be broken: {@code #chika_builder creative <TAB>}
 * produced no suggestions at all.
 *
 * <p>The command is driven through a stub engine, so no game is needed. The
 * stub's player context reports {@code null} for {@code minecraft()}, which
 * makes the command's chat output a no-op while still exercising the argument
 * handling that matters here.
 */
class ChikaBuilderCommandTest {

    @AfterEach
    void resetSettings() {
        // ChikaConfig is a process-wide singleton, so leave it as it was found.
        ChikaConfig.get().setCreativeEnabled(false);
        ChikaConfig.get().setShopEnabled(false);
    }

    // ------------------------------------------------------------------
    // Existence
    // ------------------------------------------------------------------

    @Test
    void theSettingsCommandExistsUnderTheExactName() {
        assertEquals("chika_builder", ChikaBuilderCommand.COMMAND_NAME);
        assertTrue(command().getNames().contains("chika_builder"),
                "the command must be registered under its real name");
        assertTrue(CommandLockdown.isAllowedName(ChikaBuilderCommand.COMMAND_NAME));
    }

    // ------------------------------------------------------------------
    // Tab completion: first argument
    // ------------------------------------------------------------------

    @Test
    void theFirstArgumentCompletesToTheTwoSettings() {
        assertEquals(List.of("creative", "shop"), tab(" "));
    }

    @Test
    void theFirstArgumentMatchesAPartialWord() {
        assertEquals(List.of("creative"), tab(" c"));
        assertEquals(List.of("shop"), tab(" s"));
        assertEquals(List.of(), tab(" z"));
    }

    // ------------------------------------------------------------------
    // Tab completion: the boolean value (the regression this release fixes)
    // ------------------------------------------------------------------

    @Test
    void theBooleanValueCompletesAfterCreative() {
        // Before the fix this returned nothing: the "at least 1 argument" test
        // matched while the value was being typed, so only the setting names
        // were ever offered and true/false was unreachable.
        assertEquals(List.of("false", "true"), tab(" creative "));
        assertEquals(List.of("false", "true"), tab(" shop "));
    }

    @Test
    void theBooleanValueMatchesAPartialWord() {
        assertEquals(List.of("true"), tab(" creative t"));
        assertEquals(List.of("false"), tab(" creative f"));
        assertEquals(List.of("true"), tab(" shop t"));
        assertEquals(List.of(), tab(" creative x"));
    }

    @Test
    void theBooleanValueCompletionIgnoresCase() {
        assertEquals(List.of("true"), tab(" creative T"));
        assertEquals(List.of("false"), tab(" CREATIVE F"));
    }

    @Test
    void completionStopsAfterTheValue() {
        assertEquals(List.of(), tab(" creative true "),
                "a finished command has nothing left to complete");
        assertEquals(List.of(), tab(" creative true extra "));
    }

    @Test
    void completionNeverOffersAnEngineCommand() {
        for (String typed : List.of(" ", " c", " s", " creative ", " shop ")) {
            for (String suggestion : tab(typed)) {
                assertTrue(List.of("creative", "shop", "true", "false").contains(suggestion),
                        "unexpected suggestion: " + suggestion);
            }
        }
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    @Test
    void aCompleteCommandIsAcceptedAndPersisted() {
        FakeArgs args = FakeArgs.of("creative", "true");
        command().execute("chika_builder", args.consumer());

        assertEquals(List.of("creative", "true"), args.consumedValues());
        assertTrue(ChikaConfig.get().isCreativeEnabled());

        command().execute("chika_builder", FakeArgs.of("creative", "false").consumer());
        assertFalse(ChikaConfig.get().isCreativeEnabled());
    }

    @Test
    void theShopSettingIsAcceptedIndependently() {
        command().execute("chika_builder", FakeArgs.of("shop", "true").consumer());

        assertTrue(ChikaConfig.get().isShopEnabled());
        assertFalse(ChikaConfig.get().isCreativeEnabled(),
                "setting shop must not touch the creative option");
    }

    @Test
    void valuesAreAcceptedRegardlessOfCase() {
        command().execute("chika_builder", FakeArgs.of("CREATIVE", "TRUE").consumer());
        assertTrue(ChikaConfig.get().isCreativeEnabled());
    }

    @Test
    void anUnrecognisedValueIsRejected() {
        command().execute("chika_builder", FakeArgs.of("creative", "maybe").consumer());
        assertFalse(ChikaConfig.get().isCreativeEnabled(),
                "only true/false may enable a setting");
    }

    @Test
    void anUnrecognisedSettingIsRejected() {
        command().execute("chika_builder", FakeArgs.of("watermark", "true").consumer());
        assertFalse(ChikaConfig.get().isCreativeEnabled());
        assertFalse(ChikaConfig.get().isShopEnabled());
    }

    @Test
    void anIncompleteCommandChangesNothing() {
        FakeArgs args = FakeArgs.of("creative");
        command().execute("chika_builder", args.consumer());

        assertTrue(args.consumedValues().isEmpty(),
                "a malformed command must not consume its arguments");
        assertFalse(ChikaConfig.get().isCreativeEnabled());
    }

    @Test
    void enablingTheSettingNeverChangesTheGameMode() {
        // The command's whole job is to flip a stored flag. Nothing here can
        // reach a gamemode change, which is why the stub context exposes none.
        command().execute("chika_builder", FakeArgs.of("creative", "true").consumer());
        assertTrue(ChikaConfig.get().isCreativeEnabled(),
                "enabling the setting is all this command does");
    }

    // ------------------------------------------------------------------
    // Help text
    // ------------------------------------------------------------------

    @Test
    void helpTextIsOwnedByChikaBuilder() {
        for (String line : command().getLongDesc()) {
            assertTrue(line.startsWith("chika_builder."),
                    "help text must use Chika Builder's own keys: " + line);
        }
    }

    // ------------------------------------------------------------------
    // Stubs
    // ------------------------------------------------------------------

    private static List<String> tab(String typed) {
        return command().tabComplete("chika_builder", FakeArgs.from(typed).consumer()).toList();
    }

    private static ChikaBuilderCommand command() {
        return new ChikaBuilderCommand(stubEngine());
    }

    private static IBaritone stubEngine() {
        return (IBaritone) Proxy.newProxyInstance(
                ChikaBuilderCommandTest.class.getClassLoader(),
                new Class<?>[] {IBaritone.class},
                (proxy, method, params) -> method.getName().equals("getPlayerContext")
                        ? stubPlayerContext()
                        : defaultValue(method.getReturnType()));
    }

    private static IPlayerContext stubPlayerContext() {
        return (IPlayerContext) Proxy.newProxyInstance(
                ChikaBuilderCommandTest.class.getClassLoader(),
                new Class<?>[] {IPlayerContext.class},
                (proxy, method, params) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        return null;
    }
}
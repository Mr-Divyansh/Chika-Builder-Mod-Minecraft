package dev.chika.builder.platform.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the {@code [Baritone]} -> {@code [Chika Builder]}
 * chat rebrand.
 *
 * <p>Proven from the engine's bytecode: every engine line is prefixed with
 * {@code [Baritone]} by {@code Helper.logDirect} (when
 * {@code useMessageTag=false}) and then delivered through
 * {@code Settings.logger.value.accept(...)}. This relay wraps that sink, so
 * the strip rule below is the exact transformation the player sees in game.
 */
class EngineChatRelayTest {

    @Test
    void theEngineTagIsStrippedFromEveryTagVariant() {
        assertEquals("Missing materials for at least:",
                EngineChatRelay.stripEngineTag("[Baritone] Missing materials for at least:"));
        assertEquals("Build resumed.", EngineChatRelay.stripEngineTag("[B] Build resumed."));
        assertEquals("April fools.", EngineChatRelay.stripEngineTag("[Baritoe] April fools."));
    }

    @Test
    void leadingWhitespaceIsHandled() {
        assertEquals("hello", EngineChatRelay.stripEngineTag("   [Baritone] hello"));
    }

    @Test
    void textWithoutATagIsLeftAlone() {
        assertEquals("Build complete - every block is in place.",
                EngineChatRelay.stripEngineTag("Build complete - every block is in place."));
        assertEquals("", EngineChatRelay.stripEngineTag(""));
    }

    @Test
    void nullBecomesAnEmptyLineInsteadOfCrashing() {
        assertEquals("", EngineChatRelay.stripEngineTag(null));
    }

    @Test
    void onlyALeadingTagIsRemovedSoMessageBodyIsNeverMangled() {
        assertEquals("see [Baritone] in the middle", 
                EngineChatRelay.stripEngineTag("see [Baritone] in the middle"));
    }

    @Test
    void anAlreadyRebrandedLineIsNotPrefixedTwice() {
        // Idempotence: if the sink ever runs a line through twice the player
        // must still see exactly one [Chika Builder].
        assertEquals("Build complete.", 
                EngineChatRelay.stripEngineTag("[Chika Builder] Build complete."));
        assertTrue(EngineChatRelay.stripEngineTag(
                "[Chika Builder] [Chika Builder] Build complete.")
                .contains("Build complete."));
        assertEquals("Build complete.",
                EngineChatRelay.stripEngineTag(
                        EngineChatRelay.stripEngineTag("[Baritone] [Chika Builder] Build complete.")));
    }

    @Test
    void aStrippedLineCanBeRebrandedByTheChatLayer() {
        // The full pipeline in pure text: engine line -> strip -> Chika prefix.
        String engineLine = "[Baritone] Unable to do it. Pausing. resume to resume";
        String stripped = EngineChatRelay.stripEngineTag(engineLine);
        String rendered = dev.chika.builder.ui.ChikaChat.PREFIX_TEXT + stripped;

        assertEquals("[Chika Builder] Unable to do it. Pausing. resume to resume", rendered);
    }
}
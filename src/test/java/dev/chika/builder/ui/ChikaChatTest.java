package dev.chika.builder.ui;

import dev.chika.builder.Branding;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player-facing chat contract: pink prefix, white body, and a link that
 * points at this project.
 */
class ChikaChatTest {

    @Test
    void thePrefixIsPinkAndTheBodyIsWhite() {
        MutableComponent line = ChikaChat.message("Build complete.");

        assertEquals("[Chika Builder] Build complete.", line.getString());

        // The prefix carries the brand colour...
        assertEquals(ChatFormatting.LIGHT_PURPLE.getColor(), colorOf(ChikaChat.prefix()));
        // ...and the body is plain white, never pink.
        assertEquals(ChatFormatting.WHITE.getColor(), colorOf(ChikaChat.body("Build complete.")));
    }

    @Test
    void theWholeLineIsNotColouredPink() {
        // The body is its own component, so the message cannot be all pink.
        MutableComponent body = ChikaChat.body("Missing: Dirt x57");

        assertEquals(ChatFormatting.WHITE.getColor(), colorOf(body));
    }

    @Test
    void aLinkIsWhiteUnderlinedAndClickable() {
        MutableComponent link = ChikaChat.link(Branding.REPORT_ISSUE_TEXT, Branding.ISSUES_URL);

        assertEquals(Branding.REPORT_ISSUE_TEXT, link.getString());
        assertTrue(link.getStyle().isUnderlined(), "a link should look like a link");
        assertEquals(ChatFormatting.WHITE.getColor(), colorOf(link));

        ClickEvent event = link.getStyle().getClickEvent();
        assertNotNull(event, "the issue link must be clickable");
        assertTrue(event instanceof ClickEvent.OpenUrl, event.getClass().getName());
        assertEquals(Branding.ISSUES_URL, ((ClickEvent.OpenUrl) event).uri().toString());
    }

    private static Integer colorOf(MutableComponent component) {
        return component.getStyle().getColor() == null
                ? null : component.getStyle().getColor().getValue();
    }
}
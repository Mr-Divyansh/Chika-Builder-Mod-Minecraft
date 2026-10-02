package dev.chika.builder.ui;

import dev.chika.builder.Branding;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The one place user-facing chat text is built, so no message can accidentally
 * ship without the pink Chika Builder prefix or with a stray engine name in it.
 *
 * <p>Every player-visible line goes through {@link #say(String)} or
 * {@link #message(String)}, which render as
 * {@code [Chika Builder] your text here} with the prefix in light purple
 * (Minecraft's pink) and the body in the game's own colour.
 *
 * <p>The prefix is built with the component API rather than a literal
 * {@code §d...§r} string, so the colour is applied by the client and no legacy
 * formatting codes leak into the text.
 */
public final class ChikaChat {

    /** The exact prefix players see. Also used by the branding tests. */
    public static final String PREFIX_TEXT = "[Chika Builder] ";

    /** Minecraft's pink, i.e. what {@code §d} means. */
    public static final ChatFormatting PREFIX_COLOR = ChatFormatting.LIGHT_PURPLE;

    /**
     * The colour of the message body.
     *
     * <p>Only the prefix carries the brand colour; the text itself is plain
     * white so a wall of pink is unreadable and the branding stays a single
     * recognisable element.
     */
    public static final ChatFormatting BODY_COLOR = ChatFormatting.WHITE;

    private ChikaChat() {
    }

    /** The bare prefix component, e.g. {@code [Chika Builder] }. */
    public static MutableComponent prefix() {
        return Component.literal(PREFIX_TEXT).withStyle(PREFIX_COLOR);
    }

    /** A full message: pink prefix followed by {@code text}. */
    public static MutableComponent message(String text) {
        MutableComponent line = prefix();
        line.append(body(text));
        return line;
    }

    /** The white message body, as its own component. */
    public static MutableComponent body(String text) {
        return Component.literal(text == null ? "" : text).withStyle(BODY_COLOR);
    }

    /**
     * A white, clickable link - underlined, because it is a link.
     *
     * @param text the visible label
     * @param url  the destination; must be one of this project's own URLs
     */
    public static MutableComponent link(String text, String url) {
        return Component.literal(text).withStyle(style -> style
                .withColor(BODY_COLOR)
                .withUnderlined(true)
                .withClickEvent(openUrl(url)));
    }

    /**
     * The game's own open-URL click event.
     *
     * <p>{@code ClickEvent.OpenUrl} takes a {@link java.net.URI} in this
     * version, and a malformed address must not take the message down with it.
     */
    private static net.minecraft.network.chat.ClickEvent openUrl(String url) {
        try {
            return new net.minecraft.network.chat.ClickEvent.OpenUrl(
                    java.net.URI.create(url));
        } catch (Throwable t) {
            // A link we cannot build is not worth losing the message over.
            return new net.minecraft.network.chat.ClickEvent.OpenUrl(
                    java.net.URI.create(Branding.REPOSITORY_URL));
        }
    }

    /**
     * Sends a prefixed message to the player.
     *
     * <p>Silent outside a world, so a message during load never throws.
     */
    public static void say(String text) {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();

        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(message(text));
        }
    }

    /** Sends several prefixed lines, in order. */
    public static void sayAll(Iterable<String> lines) {
        for (String line : lines) {
            say(line);
        }
    }
}
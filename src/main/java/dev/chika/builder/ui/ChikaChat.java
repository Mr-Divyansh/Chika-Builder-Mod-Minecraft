package dev.chika.builder.ui;

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

    private ChikaChat() {
    }

    /** The bare prefix component, e.g. {@code [Chika Builder] }. */
    public static MutableComponent prefix() {
        return Component.literal(PREFIX_TEXT).withStyle(PREFIX_COLOR);
    }

    /** A full message: pink prefix followed by {@code text}. */
    public static MutableComponent message(String text) {
        MutableComponent line = prefix();
        line.append(Component.literal(text == null ? "" : text));
        return line;
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
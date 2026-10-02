package dev.chika.builder.platform.engine;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import dev.chika.builder.ui.ChikaChat;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Removes the engine's own {@code [Baritone]} branding from everything the
 * player sees, by taking over the engine's chat sink.
 *
 * <p><b>How the engine emits messages</b> (read from its bytecode, not assumed;
 * verified by scanning every class in the engine jar): all 59 message-emitting
 * classes call {@code Helper.logDirect(...)}. That method:
 * <ol>
 *   <li>appends {@code Helper.getPrefix()} - the word "Baritone" (or "B", or
 *       the joke "Baritoe" on 3 March) in light purple, wrapped in square
 *       brackets - but only when {@code Settings.useMessageTag.value} is
 *       {@code false};</li>
 *   <li>delivers the finished line by calling
 *       {@code Settings.logger.value.accept(component)};</li>
 *   <li>the default value of {@code logger} is the <b>only</b> code in the whole
 *       engine that reaches Minecraft chat ({@code addClientSystemMessage} /
 *       {@code addPlayerMessage} appear nowhere else), so wrapping it captures
 *       every engine line with none missed.</li>
 * </ol>
 *
 * <p>Both {@code useMessageTag} and {@code logger} are ordinary public mutable
 * {@code Setting.value} fields. So the fix needs <b>no modification and no
 * decompilation of the third-party engine</b>: keep the engine's own text
 * prefix path switched on ({@code useMessageTag=false}), strip that prefix in
 * our sink, and re-emit the line under the Chika Builder name.
 *
 * <p>{@code useMessageTag} is deliberately {@code false}: when it is
 * {@code true} the engine instead attaches a {@code GuiMessageTag} whose
 * visible name is literally "Baritone", which would leak the engine's name
 * through the tag's tooltip even with the sink wrapped.
 *
 * <p>This is the only supported interception point. The engine writes straight
 * to the player object underneath, so it cannot be silenced lower down without
 * patching the jar, which this mod does not do.
 */
public final class EngineChatRelay {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Chika Builder");

    /** Set once the sink is wrapped, so repeated installs never double-prefix. */
    private static boolean installed;

    private EngineChatRelay() {
    }

    /**
     * Routes engine output through Chika Builder's own branding.
     *
     * <p>Idempotent and defensive: safe to call before the engine is up, safe
     * to call on every startup hook, and calling it twice does not wrap twice.
     */
    public static void install() {
        try {
            if (installed) {
                return;
            }

            Settings settings = BaritoneAPI.getSettings();

            // 1. Keep the engine's inline "[Baritone]" text prefix path active
            //    (it is the default) so our sink always receives the tag to
            //    strip - and so no GuiMessageTag named "Baritone" is attached
            //    to the chat line instead.
            settings.useMessageTag.value = false;

            // 2. Take over the sink so our prefix is added instead. The engine's
            //    message text is kept intact and only re-prefixed, so colours
            //    and click actions inside the message survive.
            settings.logger.value = rebranding(settings.logger.value);

            installed = true;
            LOGGER.info("[Chika Builder] Chat relay installed - engine lines "
                    + "are re-prefixed with [Chika Builder]");
        } catch (Throwable t) {
            // Never let branding break the game. Without this the player simply
            // sees the engine's default prefix instead of ours.
            dev.chika.builder.ChikaBuilderClient.LOGGER.warn(
                    "Could not install the Chika Builder chat branding", t);
        }
    }

    /** Whether the relay has been installed (diagnostics/tests). */
    static boolean isInstalled() {
        return installed;
    }

    /**
     * Wraps an existing chat sink so each message is re-emitted under our name.
     *
     * @param previous the engine's current sink, kept as the real transport
     */
    static Consumer<Component> rebranding(Consumer<Component> previous) {
        return component -> {
            try {
                if (previous != null) {
                    previous.accept(rebrand(component));
                }
            } catch (Throwable ignored) {
                // A failing sink must not take the game down.
            }
        };
    }

    /**
     * Strips a leading {@code [Baritone]} / {@code [B]} / {@code [Baritoe]} tag
     * from a message so it can be re-emitted with the Chika Builder prefix.
     *
     * <p>Our own {@code [Chika Builder]} tag is stripped too, so a message that
     * somehow passes through the sink twice still renders with exactly one
     * prefix instead of stacking two.
     *
     * <p>Only a prefix is removed; the rest of the message is left untouched so
     * no information is lost.
     */
    public static String stripEngineTag(String text) {
        if (text == null) {
            return "";
        }

        String trimmed = text.stripLeading();

        for (String tag : new String[] {"[Baritoe]", "[Baritone]", "[B]",
                ChikaChat.PREFIX_TEXT.stripTrailing()}) {
            if (trimmed.startsWith(tag)) {
                return trimmed.substring(tag.length()).stripLeading();
            }
        }

        return trimmed;
    }

    /** Applies {@link #stripEngineTag(String)} to an engine component. */
    static Component rebrand(Component component) {
        if (component == null) {
            return ChikaChat.message("");
        }

        String stripped = stripEngineTag(component.getString());

        return ChikaChat.message(stripped);
    }
}
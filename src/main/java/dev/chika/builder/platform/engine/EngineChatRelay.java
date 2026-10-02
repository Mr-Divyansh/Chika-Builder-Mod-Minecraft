package dev.chika.builder.platform.engine;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import dev.chika.builder.Branding;
import dev.chika.builder.ui.ChikaChat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

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

    /** Applies {@link #stripEngineTag(String)} then {@link #scrubUpstream(String)}. */
    static Component rebrand(Component component) {
        if (component == null) {
            return ChikaChat.message("");
        }

        String original = component.getString();
        String text = scrubUpstream(stripEngineTag(original));

        MutableComponent line = ChikaChat.message(text);

        // The engine's help/issue links point at its own project. Those are
        // replaced in the text above, and the destination is re-attached here
        // as our own clickable link, so a player who follows it lands on this
        // project's issue tracker.
        if (mentionsUpstreamLink(original) || reportsProblem(original)) {
            line.append(Component.literal(" "));
            line.append(ChikaChat.link(Branding.REPORT_ISSUE_TEXT, Branding.ISSUES_URL));
        }

        return line;
    }

    /**
     * Removes every trace of the bundled engine's public identity from
     * player-facing text.
     *
     * <p>Two things are rewritten:
     * <ul>
     *   <li><b>URLs</b> that point at the engine's own project become this
     *       project's issue page. The engine prints one verbatim in its
     *       unhandled-exception message
     *       ("...please report this at https://github.com/&lt;upstream&gt;/issues"),
     *       and that line reaches the player's chat through the very sink this
     *       relay wraps.</li>
     *   <li><b>The engine's name</b>, in any casing, including the 3 March
     *       "Baritoe" variant, becomes the Chika Builder product name.</li>
     * </ul>
     *
     * <p>Pure string handling, so every rule is unit testable.
     */
    public static String scrubUpstream(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String out = replaceUpstreamUrls(text);

        // The engine's settings file is not the player's file - point at ours.
        out = out.replaceAll("(?i)baritone\\.properties", "chika-builder.json");
        out = out.replaceAll("(?i)\\bbaritoe\\b|\\bbaritone\\b", Branding.PRODUCT_NAME);

        return out;
    }

    /** True when the text carries a link that belongs to the bundled engine. */
    public static boolean mentionsUpstreamLink(String text) {
        if (text == null) {
            return false;
        }

        java.util.regex.Matcher matcher = URL.matcher(text);

        while (matcher.find()) {
            if (isUpstreamUrl(matcher.group())) {
                return true;
            }
        }

        return false;
    }

    /** True when the line is asking the player to report a problem. */
    public static boolean reportsProblem(String text) {
        if (text == null) {
            return false;
        }

        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("report this") || lower.contains("please report");
    }

    private static String replaceUpstreamUrls(String text) {
        java.util.regex.Matcher matcher = URL.matcher(text);
        StringBuffer out = new StringBuffer();

        while (matcher.find()) {
            String url = matcher.group();
            String replacement = isUpstreamUrl(url) ? Branding.ISSUES_URL : url;
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * True when a URL points at the bundled engine's own project.
     *
     * <p>The upstream name appears here only as a <b>filter</b>: this is the
     * pattern Chika Builder recognises in order to rewrite it away, never a link
     * shown to a player. Anything it matches is replaced with
     * {@link Branding#ISSUES_URL}.
     */
    private static boolean isUpstreamUrl(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(UPSTREAM_PROJECT) || lower.contains(UPSTREAM_OWNER);
    }

    /** The engine's own project name, used only to recognise and hide it. */
    private static final String UPSTREAM_PROJECT = "baritone";

    /** The engine's repository owner, used only to recognise and hide it. */
    private static final String UPSTREAM_OWNER = "cabaletta";

    private static final java.util.regex.Pattern URL =
            java.util.regex.Pattern.compile("https?://\\S+");
}
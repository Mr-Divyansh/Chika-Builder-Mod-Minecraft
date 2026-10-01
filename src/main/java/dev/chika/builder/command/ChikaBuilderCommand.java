package dev.chika.builder.command;

import dev.chika.builder.config.ChikaConfig;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;

import java.util.List;
import java.util.stream.Stream;

/**
 * {@code #chika_builder} — Chika Builder's settings command.
 *
 * <pre>
 *   #chika_builder creative true | false
 *   #chika_builder shop     true | false
 * </pre>
 *
 * <p>This is a Chika Builder command, not an engine command: it never enables an
 * engine feature and it is never about pathing. It only flips a persisted
 * setting, so a typo cannot start a build.
 */
public final class ChikaBuilderCommand extends Command {

    /** The exact user-facing name required by the specification. */
    public static final String COMMAND_NAME = "chika_builder";

    private static final String ARG_CREATIVE = "creative";
    private static final String ARG_SHOP = "shop";

    private static final List<String> OPTIONS = List.of("creative", "shop");

    public ChikaBuilderCommand(IBaritone engine) {
        super(engine, COMMAND_NAME);
    }

    @Override
    public String getShortDesc() {
        return "Chika Builder settings";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "chika_builder.settings.usage",
                "chika_builder.settings.desc"
        );
    }

    @Override
    public void execute(String commandName, IArgConsumer args) {
        if (!args.hasExactly(2)) {
            say("Usage: #" + COMMAND_NAME + " creative|shop true|false");
            return;
        }

        // Read exactly once: IArgConsumer.getString() advances the cursor, so
        // asking again would hand back the *value* instead of the setting name.
        String rawSetting = args.getString();
        String setting = rawSetting.toLowerCase(java.util.Locale.ROOT);

        if (!OPTIONS.contains(setting)) {
            say("Unknown setting '" + rawSetting + "'. Use: creative or shop.");
            return;
        }

        String rawValue = args.getString().toLowerCase(java.util.Locale.ROOT);

        Boolean value = parseBoolean(rawValue);
        if (value == null) {
            say("Value must be true or false, not '" + rawValue + "'.");
            return;
        }

        ChikaConfig config = ChikaConfig.get();

        if (ARG_CREATIVE.equals(setting)) {
            config.setCreativeEnabled(value);
            say("Creative building " + (value ? "enabled" : "disabled") + ".");
            if (value) {
                say("Note: this never changes your gamemode. You must already be in Creative.");
            }
        } else {
            config.setShopEnabled(value);
            say("Auto-shop " + (value ? "enabled" : "disabled") + ".");
        }
    }

    @Override
    public Stream<String> tabComplete(String commandName, IArgConsumer args) {
        if (args.has(1)) {
            return OPTIONS.stream().filter(name -> name.startsWith(
                    args.peekString().toLowerCase(java.util.Locale.ROOT))).sorted();
        }
        if (args.has(2)) {
            return Stream.of("true", "false").filter(value -> value.startsWith(
                    args.peekString().toLowerCase(java.util.Locale.ROOT))).sorted();
        }
        return Stream.empty();
    }

    /**
     * Accepts only the two documented spellings.
     *
     * @return the value, or {@code null} when the text is not a boolean
     */
    private static Boolean parseBoolean(String raw) {
        if ("true".equals(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equals(raw)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private void say(String message) {
        if (this.ctx.minecraft() != null && this.ctx.minecraft().player != null) {
            this.ctx.minecraft().player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(message));
        }
    }
}

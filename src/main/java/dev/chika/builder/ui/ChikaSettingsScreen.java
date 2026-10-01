package dev.chika.builder.ui;

import dev.chika.builder.Branding;
import dev.chika.builder.config.ChikaConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Chika Builder settings + about screen.
 *
 * <p>This is the primary home for the D Web Studio branding, so the watermark
 * credit is always visible without spamming chat. It also holds the persisted
 * settings:
 * <ul>
 *   <li>D Web Studio watermark ON/OFF (default ON)</li>
 *   <li>Creative-mode building ON/OFF (default OFF)</li>
 *   <li>Auto-shop ON/OFF (default OFF)</li>
 * </ul>
 *
 * <p>Every toggle writes through {@link ChikaConfig}, so the values survive a
 * restart. The same settings are available in chat via
 * {@code #chika_builder creative|shop true|false}.
 */
public final class ChikaSettingsScreen extends Screen {

    private static final Component TITLE = Component.literal(Branding.PRODUCT_NAME);

    /** Row height plus the gap between buttons, in pixels. */
    private static final int ROW = 24;

    private final Screen parent;

    public ChikaSettingsScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centreX = this.width / 2;
        int rowY = this.height / 2 - 28;

        // Creative-mode building. The screen only flips the preference; the
        // player's actual gamemode is never changed by Chika Builder.
        this.addRenderableWidget(Button.builder(
                        creativeButtonText(),
                        button -> {
                            ChikaConfig.get().setCreativeEnabled(!ChikaConfig.get().isCreativeEnabled());
                            button.setMessage(creativeButtonText());
                        })
                .bounds(centreX - 100, rowY, 200, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                        shopButtonText(),
                        button -> {
                            ChikaConfig.get().setShopEnabled(!ChikaConfig.get().isShopEnabled());
                            button.setMessage(shopButtonText());
                        })
                .bounds(centreX - 100, rowY + ROW, 200, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                        watermarkButtonText(),
                        button -> {
                            ChikaConfig.get().toggleWatermark();
                            button.setMessage(watermarkButtonText());
                        })
                .bounds(centreX - 100, rowY + ROW * 2, 200, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(centreX - 100, rowY + ROW * 3 + 8, 200, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int centreX = this.width / 2;

        graphics.centeredText(this.font, Branding.PRODUCT_DISPLAY, centreX,
                this.height / 2 - 56, 0xFFFFFFFF);
        // Brand credit, kept small and understated.
        graphics.centeredText(this.font, Branding.PRODUCT_BYLINE, centreX,
                this.height / 2 - 44, 0x88FFFFFF);

        graphics.centeredText(this.font,
                Component.literal("Build: #chika_build <file>.schematic"),
                centreX, this.height / 2 + 56, 0x808080);
        graphics.centeredText(this.font,
                Component.literal("Settings: #chika_builder creative|shop true|false"),
                centreX, this.height / 2 + 68, 0x808080);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private static Component watermarkButtonText() {
        return label("D Web Studio Watermark", ChikaConfig.get().isWatermarkEnabled());
    }

    private static Component creativeButtonText() {
        return label("Creative Building", ChikaConfig.get().isCreativeEnabled());
    }

    private static Component shopButtonText() {
        return label("Auto-Shop", ChikaConfig.get().isShopEnabled());
    }

    /** {@code "Creative Building: OFF"} - self-describing, so no label is needed. */
    private static Component label(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "ON" : "OFF"));
    }
}
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
 * credit is always visible without spamming chat. It also holds the single
 * setting: the D Web Studio watermark ON/OFF toggle (default ON).
 */
public final class ChikaSettingsScreen extends Screen {

    private static final Component TITLE = Component.literal(Branding.PRODUCT_NAME);

    private final Screen parent;

    public ChikaSettingsScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centreX = this.width / 2;
        int rowY = this.height / 2 - 10;

        this.addRenderableWidget(Button.builder(
                        watermarkButtonText(),
                        button -> {
                            ChikaConfig.get().toggleWatermark();
                            button.setMessage(watermarkButtonText());
                        })
                .bounds(centreX - 100, rowY, 200, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(centreX - 100, rowY + 32, 200, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int centreX = this.width / 2;

        graphics.centeredText(this.font, Branding.PRODUCT_DISPLAY, centreX,
                this.height / 2 - 46, 0xFFFFFFFF);
        // Brand credit, kept small and understated.
        graphics.centeredText(this.font, Branding.PRODUCT_BYLINE, centreX,
                this.height / 2 - 34, 0x88FFFFFF);

        graphics.centeredText(this.font, Component.literal("D Web Studio Watermark"),
                centreX, this.height / 2 - 20, 0xBFBFBFBF);

        graphics.centeredText(this.font,
                Component.literal("Build a schematic with #chika_build <file>.schematic"),
                centreX, this.height / 2 + 30, 0x808080);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private static Component watermarkButtonText() {
        return Component.literal(ChikaConfig.get().isWatermarkEnabled() ? "ON" : "OFF");
    }
}
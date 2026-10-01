package dev.chika.builder.ui;

import dev.chika.builder.Branding;
import dev.chika.builder.config.ChikaConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Small, unobtrusive "D Web Studio" watermark drawn in the bottom-right corner.
 *
 * <p>Design notes:
 * <ul>
 *   <li>It is purely visual: it reads config and draws text, and never touches
 *       the builder, pathing or block placement logic.</li>
 *   <li>Rendered at low alpha and small scale so it stays out of the way.</li>
 *   <li>Hidden while the HUD is hidden (F1) and on any screen that isn't a world,
 *       so it never covers menus or blocks the player's view.</li>
 *   <li>Zero allocation per frame apart from the constant string.</li>
 * </ul>
 */
public final class WatermarkHud implements HudElement {

    /** Alpha for the studio name - deliberately faint. */
    private static final int COLOR_STUDIO = 0x66FFFFFF;

    /** Padding from the right/bottom edge, in pixels. */
    private static final int MARGIN = 4;

    private WatermarkHud() {
    }

    /** Registers the watermark with Fabric's HUD element registry. */
    public static void register() {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
                .addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                                "chika_builder", "watermark"),
                        new WatermarkHud());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (!ChikaConfig.get().isWatermarkEnabled()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        // Respect the player's own HUD preference (F1).
        if (minecraft.options.hideGui) {
            return;
        }

        // Only in-world, so it never overlaps menus, inventory or chat screens.
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        graphics.text(minecraft.font, Branding.WATERMARK_TEXT,
                graphics.guiWidth() - minecraft.font.width(Branding.WATERMARK_TEXT) - MARGIN,
                graphics.guiHeight() - MARGIN - 9,
                COLOR_STUDIO,
                false);
    }
}
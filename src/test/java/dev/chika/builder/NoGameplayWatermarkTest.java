package dev.chika.builder;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests proving the gameplay HUD carries no watermark.
 *
 * <p>A live test showed a faint "D Web Studio" watermark in the bottom-right of
 * the gameplay screen. It came from {@code WatermarkHud}, a Fabric
 * {@code HudElement} registered from {@code ChikaBuilderClient} and drawn with
 * {@code graphics.text(...)}. That renderer has been deleted rather than
 * hidden, so there is no HUD render path left to disable later.
 *
 * <p>These tests scan the real source tree, so they fail if anyone re-adds a HUD
 * element, re-registers one, or draws the studio name on screen again - not just
 * if they re-add this particular class.
 *
 * <p>Deliberately <b>not</b> asserted here: that "D Web Studio" is absent from
 * the README, docs or mod metadata. The studio identity is meant to stay in
 * documentation and credits; only the on-screen overlay is gone.
 */
class NoGameplayWatermarkTest {

    /** Every main source file, so the scan cannot be dodged by moving code. */
    private static List<Path> mainSources() throws IOException {
        Path root = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(root), "expected main sources at " + root.toAbsolutePath());

        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = NoGameplayWatermarkTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theWatermarkRendererClassNoLongerExists() {
        // The strongest statement available: the type is gone from the source
        // tree, not merely unused.
        assertFalse(Files.exists(Path.of("src", "main", "java", "dev", "chika", "builder",
                        "ui", "WatermarkHud.java")),
                "the HUD watermark renderer must be deleted, not just unregistered");
    }

    @Test
    void noHudElementIsRegisteredAnywhereInTheMod() throws IOException {
        for (Path path : mainSources()) {
            String source = read(path);
            assertFalse(source.contains("HudElementRegistry"),
                    path + " registers a HUD element; the gameplay HUD must stay free of overlays");
            assertFalse(source.contains("HudElement"),
                    path + " declares a HUD element; the gameplay HUD must stay free of overlays");
        }
    }

    @Test
    void theStudioNameIsNeverDrawnOnScreen() throws IOException {
        // Guards the whole draw path, not just the deleted class: no gameplay
        // source may pass the studio name (or the watermark constant) to a
        // text/render call.
        for (Path path : mainSources()) {
            String source = read(path);

            if (source.contains("Branding.WATERMARK_TEXT")) {
                assertFalse(source.contains("graphics.text"),
                        path + " still draws Branding.WATERMARK_TEXT on screen");
            }
            assertFalse(source.contains("graphics.text(font, Branding.STUDIO_NAME"),
                    path + " must not draw the studio name on the HUD");
        }
    }

    @Test
    void theClientRegistersNoHudOverlay() throws IOException {
        String client = read(Path.of("src", "main", "java", "dev", "chika", "builder",
                "ChikaBuilderClient.java"));

        // Comments are allowed to explain that the watermark was removed, so
        // this checks executable code only: an import or a call.
        assertFalse(client.contains("import dev.chika.builder.ui.WatermarkHud"),
                "the client must not import the removed watermark renderer");
        assertFalse(client.contains("WatermarkHud.register()"),
                "the client must not register the removed watermark renderer");
        assertFalse(client.contains("new WatermarkHud"),
                "the client must not construct the removed watermark renderer");
        assertFalse(client.contains("HudElementRegistry"),
                "the client must not register a HUD element of any kind");
    }

    @Test
    void theSettingsScreenOffersNoWatermarkToggle() throws IOException {
        // A toggle for a feature that no longer exists would be a lie.
        String screen = read(Path.of("src", "main", "java", "dev", "chika", "builder",
                "ui", "ChikaSettingsScreen.java"));

        assertFalse(screen.contains("toggleWatermark"),
                "the settings screen must not offer a watermark toggle");
        assertFalse(screen.contains("Watermark"),
                "the settings screen must not mention a watermark");
    }

    @Test
    void theWatermarkTranslationKeysAreRemoved() throws IOException {
        String lang = readResource("/assets/chika-builder/lang/en_us.json").toLowerCase(Locale.ROOT);

        assertFalse(lang.contains("watermark"),
                "watermark translation keys must be removed with the feature");
    }

    @Test
    void theStudioCreditStillExistsInMetadata() throws IOException {
        // The inverse guarantee: only the overlay was removed. D Web Studio
        // must still be credited in the mod metadata.
        String metadata = readResource("/fabric.mod.json");

        assertTrue(metadata.contains("D Web Studio"),
                "the studio must stay credited in the mod metadata");
        assertTrue(metadata.contains("\"name\": \"Chika Builder\""),
                "the product name must remain Chika Builder: " + metadata);
    }

    @Test
    void theProductAndStudioConstantsAreUnchanged() {
        assertEquals("D Web Studio", Branding.STUDIO_NAME,
                "the studio name constant must remain for documentation and credits");
        assertEquals("CHIKA BUILDER", Branding.PRODUCT_DISPLAY);
        assertEquals("by D Web Studio", Branding.PRODUCT_BYLINE);
    }
}

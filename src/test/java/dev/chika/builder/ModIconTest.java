package dev.chika.builder;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Chika Builder mod icon.
 *
 * <p>Without an {@code icon} entry in fabric.mod.json the Mods screen falls back
 * to the generic "?" placeholder, so these assertions pin the metadata key, the
 * resource path it points at, and the shape of the image itself.
 */
class ModIconTest {

    /** Fabric resolves this key from the mod's own jar, with no leading slash. */
    private static final String EXPECTED_ICON = "assets/chika-builder/icon.png";

    /** Minimum edge Fabric recommends for a mod icon. */
    private static final int MIN_SIZE = 128;

    @Test
    void fabricModJsonDeclaresTheIcon() throws IOException {
        String metadata = readResource("/fabric.mod.json");
        assertTrue(metadata.contains("\"icon\""),
                "fabric.mod.json must declare an \"icon\" or the Mods screen shows \"?\"");
        assertTrue(metadata.contains("\"" + EXPECTED_ICON + "\""),
                "icon must point at " + EXPECTED_ICON);
    }

    @Test
    void declaredIconMatchesTheModId() throws IOException {
        // The icon lives under assets/<mod id>/, so the folder name and the
        // fabric.mod.json "id" have to stay in sync or the lookup fails.
        String metadata = readResource("/fabric.mod.json");

        Matcher id = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(metadata);
        assertTrue(id.find(), "fabric.mod.json must declare an \"id\"");
        assertEquals("chika-builder", id.group(1), "mod id changed; the icon folder must follow it");

        Matcher icon = Pattern.compile("\"icon\"\\s*:\\s*\"([^\"]+)\"").matcher(metadata);
        assertTrue(icon.find(), "fabric.mod.json must declare an \"icon\"");
        assertEquals(EXPECTED_ICON, icon.group(1));
        assertTrue(icon.group(1).startsWith("assets/" + id.group(1) + "/"),
                "icon must live under assets/" + id.group(1) + "/");
    }

    @Test
    void iconResourceIsPackaged() throws IOException {
        assertNotNull(ModIconTest.class.getResource("/" + EXPECTED_ICON),
                "missing packaged resource: " + EXPECTED_ICON);
    }

    @Test
    void iconIsAReadablePng() throws IOException {
        try (InputStream in = ModIconTest.class.getResourceAsStream("/" + EXPECTED_ICON)) {
            assertNotNull(in, "icon resource could not be opened");
            BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "icon is not a decodable image (must be PNG)");
        }
    }

    @Test
    void iconIsSquareAndLargeEnough() throws IOException {
        try (InputStream in = ModIconTest.class.getResourceAsStream("/" + EXPECTED_ICON)) {
            assertNotNull(in, "icon resource could not be opened");
            BufferedImage image = ImageIO.read(in);

            // Square: Fabric draws the icon in a square slot, so a non-square
            // image would be letterboxed or distorted. It also proves the logo
            // was scaled uniformly rather than stretched.
            assertEquals(image.getWidth(), image.getHeight(),
                    "icon must be square (width != height means it was stretched)");
            assertTrue(image.getWidth() >= MIN_SIZE,
                    "icon must be at least " + MIN_SIZE + "px so it stays crisp in the Mods screen");
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = ModIconTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing packaged resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

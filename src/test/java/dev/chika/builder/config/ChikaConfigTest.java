package dev.chika.builder.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the persisted settings.
 *
 * <p>The old D Web Studio watermark setting was removed with the HUD overlay.
 * Its key is still accepted on read so existing config files keep loading, but
 * it is no longer written and controls nothing; the tests below pin both halves
 * of that contract.
 */
class ChikaConfigTest {

    @Test
    void anExistingWatermarkKeyStillLoadsWithoutFailing(@TempDir Path dir) throws IOException {
        // Backward compatibility: a player who already has the key in their
        // config must not lose their other settings, and the file must still
        // load even though the key now controls nothing.
        Path file = dir.resolve("chika-builder.json");
        Files.writeString(file, "{\"watermarkEnabled\": false, \"creativeEnabled\": true}",
                StandardCharsets.UTF_8);

        ChikaConfig config = ChikaConfig.loadFrom(file);

        assertTrue(config.isCreativeEnabled(),
                "an existing file must still load its live settings");
        assertFalse(config.isWatermarkEnabled(),
                "the retired key is still parsed, it simply has no effect");
    }

    @Test
    void theRetiredWatermarkKeyIsNoLongerWritten(@TempDir Path dir) throws IOException {
        // The watermark is gone, so persisting a dead key would only mislead
        // anyone reading their own config file.
        Path file = dir.resolve("chika-builder.json");
        ChikaConfig config = ChikaConfig.loadFrom(file);
        config.setWatermarkEnabled(false);
        config.save();

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(json.contains("watermarkEnabled"),
                "the removed watermark must not be written back: " + json);
    }

    @Test
    void creativeAndShopKeysAreStillPersisted(@TempDir Path dir) throws IOException {
        // The settings that still exist must keep working exactly as before.
        Path file = dir.resolve("chika-builder.json");
        ChikaConfig config = ChikaConfig.loadFrom(file);
        config.setCreativeEnabled(true);
        config.setShopEnabled(true);

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("creativeEnabled"), json);
        assertTrue(json.contains("shopEnabled"), json);
    }

    @Test
    void corruptConfigFallsBackToSafeDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("chika-builder.json");
        Files.writeString(file, "{ this is not valid json", StandardCharsets.UTF_8);

        ChikaConfig config = ChikaConfig.loadFrom(file);
        assertFalse(config.isCreativeEnabled(), "corrupt config must fall back to OFF");
        assertFalse(config.isShopEnabled(), "corrupt config must fall back to OFF");
    }

    // -----------------------------------------------------------------
    // Creative and auto-shop settings
    // -----------------------------------------------------------------

    @Test
    void creativeDefaultsToOff(@TempDir Path dir) {
        ChikaConfig config = ChikaConfig.loadFrom(dir.resolve("chika-builder.json"));
        assertFalse(config.isCreativeEnabled(), "creative must default to OFF");
    }

    @Test
    void shopDefaultsToOff(@TempDir Path dir) {
        ChikaConfig config = ChikaConfig.loadFrom(dir.resolve("chika-builder.json"));
        assertFalse(config.isShopEnabled(), "auto-shop must default to OFF");
    }

    @Test
    void creativeSettingPersistsAcrossReloads(@TempDir Path dir) {
        Path file = dir.resolve("chika-builder.json");

        ChikaConfig first = ChikaConfig.loadFrom(file);
        first.setCreativeEnabled(true);
        assertTrue(ChikaConfig.loadFrom(file).isCreativeEnabled(), "creative=true must persist");

        first.setCreativeEnabled(false);
        assertFalse(ChikaConfig.loadFrom(file).isCreativeEnabled(), "creative=false must persist");
    }

    @Test
    void shopSettingPersistsAcrossReloads(@TempDir Path dir) {
        Path file = dir.resolve("chika-builder.json");

        ChikaConfig first = ChikaConfig.loadFrom(file);
        first.setShopEnabled(true);
        assertTrue(ChikaConfig.loadFrom(file).isShopEnabled(), "shop=true must persist");

        first.setShopEnabled(false);
        assertFalse(ChikaConfig.loadFrom(file).isShopEnabled(), "shop=false must persist");
    }

    @Test
    void savedFileContainsBothNewSettings(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("chika-builder.json");
        ChikaConfig config = ChikaConfig.loadFrom(file);
        config.setCreativeEnabled(true);
        config.setShopEnabled(true);

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("creativeEnabled"), json);
        assertTrue(json.contains("shopEnabled"), json);
    }

    @Test
    void corruptConfigResetsCreativeAndShopToOff(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("chika-builder.json");
        Files.writeString(file, "not json at all", StandardCharsets.UTF_8);

        ChikaConfig config = ChikaConfig.loadFrom(file);
        assertFalse(config.isCreativeEnabled());
        assertFalse(config.isShopEnabled());
    }

    @Test
    void anOlderConfigWithoutTheNewKeysStillLoads(@TempDir Path dir) throws IOException {
        // A config written before these settings existed must load cleanly and
        // keep the new options at their safe defaults.
        Path file = dir.resolve("chika-builder.json");
        Files.writeString(file, "{\"watermarkEnabled\": false}", StandardCharsets.UTF_8);

        ChikaConfig config = ChikaConfig.loadFrom(file);
        assertFalse(config.isWatermarkEnabled(), "the existing key must still be honoured");
        assertFalse(config.isCreativeEnabled());
        assertFalse(config.isShopEnabled());
    }
}
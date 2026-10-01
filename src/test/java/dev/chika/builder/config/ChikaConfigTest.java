package dev.chika.builder.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the configurable D Web Studio watermark setting. */
class ChikaConfigTest {

    @Test
    void watermarkDefaultsToOn(@TempDir Path dir) {
        ChikaConfig config = ChikaConfig.loadFrom(dir.resolve("chika-builder.json"));
        assertTrue(config.isWatermarkEnabled(), "watermark must default to ON");
    }

    @Test
    void toggleFlipsWatermark(@TempDir Path dir) {
        ChikaConfig config = ChikaConfig.loadFrom(dir.resolve("chika-builder.json"));

        config.toggleWatermark();
        assertFalse(config.isWatermarkEnabled());

        config.toggleWatermark();
        assertTrue(config.isWatermarkEnabled());
    }

    @Test
    void settingIsPersistedAcrossReloads(@TempDir Path dir) {
        Path file = dir.resolve("chika-builder.json");

        ChikaConfig first = ChikaConfig.loadFrom(file);
        first.setWatermarkEnabled(false);

        // A fresh load must observe the saved OFF state.
        ChikaConfig reloaded = ChikaConfig.loadFrom(file);
        assertFalse(reloaded.isWatermarkEnabled(), "watermark OFF must persist");
    }

    @Test
    void savedFileContainsTheToggle(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("chika-builder.json");
        ChikaConfig config = ChikaConfig.loadFrom(file);
        config.setWatermarkEnabled(false);

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("watermarkEnabled"), json);
        assertTrue(json.contains("false"), json);
    }

    @Test
    void corruptConfigFallsBackToDefaultOn(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("chika-builder.json");
        Files.writeString(file, "{ this is not valid json", StandardCharsets.UTF_8);

        ChikaConfig config = ChikaConfig.loadFrom(file);
        assertTrue(config.isWatermarkEnabled(), "corrupt config must fall back to ON");
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
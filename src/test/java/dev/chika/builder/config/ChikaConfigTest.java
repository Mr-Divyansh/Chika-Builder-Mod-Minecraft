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
}
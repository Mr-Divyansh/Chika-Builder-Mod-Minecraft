package dev.chika.builder.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tiny persisted settings holder.
 *
 * <p>Kept deliberately minimal and cheap: values are read once at startup and
 * only written when the player actually changes a setting, so the watermark
 * costs nothing per frame and never touches the build logic.
 */
public final class ChikaConfig {

    /** D Web Studio watermark toggle. Defaults to ON. */
    private boolean watermarkEnabled = true;

    /**
     * Whether Chika Builder may use Creative-mode building logic.
     *
     * <p>Defaults to {@code false}: survival building is the expected behaviour.
     *
     * <p>This flag never changes the player's gamemode. It only tells the
     * material planner that Creative mechanics may be used, and even then only
     * when the player is <em>actually</em> in Creative.
     */
    private boolean creativeEnabled = false;

    /**
     * Whether missing materials may be bought automatically.
     *
     * <p>Defaults to {@code false}: automatic spending must be opted into.
     */
    private boolean shopEnabled = false;

    private static final Logger LOGGER = LoggerFactory.getLogger("ChikaBuilder");

    private static ChikaConfig instance = new ChikaConfig(null);

    /**
     * Where the settings file lives. Resolved lazily rather than in a static
     * initialiser, so loading this class never depends on Fabric Loader being
     * ready (and so it stays unit-testable).
     */
    private final Path path;

    private ChikaConfig(Path path) {
        this.path = path;
    }

    public static ChikaConfig get() {
        return instance;
    }

    public boolean isWatermarkEnabled() {
        return this.watermarkEnabled;
    }

    public void setWatermarkEnabled(boolean enabled) {
        if (this.watermarkEnabled == enabled) {
            return;
        }
        this.watermarkEnabled = enabled;
        save();
    }

    public void toggleWatermark() {
        setWatermarkEnabled(!this.watermarkEnabled);
    }

    // ---------------------------------------------------------------------
    // Creative-mode building
    // ---------------------------------------------------------------------

    /** True when Creative-mode building is allowed. Does NOT imply the player is in Creative. */
    public boolean isCreativeEnabled() {
        return this.creativeEnabled;
    }

    public void setCreativeEnabled(boolean enabled) {
        if (this.creativeEnabled == enabled) {
            return;
        }
        this.creativeEnabled = enabled;
        save();
    }

    // ---------------------------------------------------------------------
    // Auto-shop
    // ---------------------------------------------------------------------

    /** True when missing materials may be purchased automatically. */
    public boolean isShopEnabled() {
        return this.shopEnabled;
    }

    public void setShopEnabled(boolean enabled) {
        if (this.shopEnabled == enabled) {
            return;
        }
        this.shopEnabled = enabled;
        save();
    }

    /** The settings file location, or null when it cannot be resolved. */
    public Path path() {
        if (this.path != null) {
            return this.path;
        }
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("chika-builder.json");
        } catch (Throwable t) {
            // Loader unavailable (e.g. unit tests); settings simply stay in memory.
            return null;
        }
    }

    /** Loads settings from the standard location, falling back to defaults. */
    public static void load() {
        try {
            instance = loadFrom(FabricLoader.getInstance().getConfigDir().resolve("chika-builder.json"));
        } catch (Throwable t) {
            LOGGER.warn("Could not resolve config path - using defaults", t);
            instance = new ChikaConfig(null);
        }
    }

    /** Loads settings from an explicit file. Any problem falls back to defaults. */
    public static ChikaConfig loadFrom(Path file) {
        ChikaConfig loaded = new ChikaConfig(file);

        if (file == null || !Files.isRegularFile(file)) {
            // No config yet: defaults apply (watermark ON).
            loaded.save();
            return loaded;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("watermarkEnabled") && json.get("watermarkEnabled").isJsonPrimitive()) {
                loaded.watermarkEnabled = json.get("watermarkEnabled").getAsBoolean();
            }
            // Absent keys keep their default (false), so a config written by an
            // older version still loads cleanly.
            if (json.has("creativeEnabled") && json.get("creativeEnabled").isJsonPrimitive()) {
                loaded.creativeEnabled = json.get("creativeEnabled").getAsBoolean();
            }
            if (json.has("shopEnabled") && json.get("shopEnabled").isJsonPrimitive()) {
                loaded.shopEnabled = json.get("shopEnabled").getAsBoolean();
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read {} - using defaults", file, e);
            loaded.watermarkEnabled = true;
            loaded.creativeEnabled = false;
            loaded.shopEnabled = false;
        }

        return loaded;
    }

    /** Writes settings to disk; failures are logged but never crash the game. */
    public void save() {
        Path file = path();
        if (file == null) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            JsonObject json = new JsonObject();
            json.addProperty("watermarkEnabled", this.watermarkEnabled);
            json.addProperty("creativeEnabled", this.creativeEnabled);
            json.addProperty("shopEnabled", this.shopEnabled);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write(json.toString());
            }
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", file, e);
        }
    }
}
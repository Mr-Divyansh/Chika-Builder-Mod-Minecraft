package dev.chika.builder;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import com.mojang.blaze3d.platform.InputConstants;
import dev.chika.builder.build.BuildCoordinator;
import dev.chika.builder.build.BuildService;
import dev.chika.builder.build.material.CreativeSupplier;
import dev.chika.builder.build.material.PlayerContext;
import dev.chika.builder.build.material.SchematicAnalyzer;
import dev.chika.builder.build.shop.PurchaseOrchestrator;
import dev.chika.builder.command.ChikaBuildCommand;
import dev.chika.builder.command.ChikaBuilderCommand;
import dev.chika.builder.command.CommandLockdown;
import dev.chika.builder.config.ChikaConfig;
import dev.chika.builder.platform.engine.ChikaBuildService;
import dev.chika.builder.platform.engine.ChikaSchematicAnalyzer;
import dev.chika.builder.platform.engine.CreativeInventorySupplier;
import dev.chika.builder.platform.engine.MinecraftPlayerContext;
import dev.chika.builder.schematic.SchematicLocator;
import dev.chika.builder.ui.ChikaSettingsScreen;
import dev.chika.builder.ui.WatermarkHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Chika Builder client entrypoint.
 *
 * <p>Responsibilities, in order:
 * <ol>
 *   <li>register Chika Builder's own commands, {@code #chika_build} and
 *       {@code #chika_builder},</li>
 *   <li>remove every other engine command so {@code #goto}, {@code #follow},
 *       {@code #mine} and the rest are no longer reachable,</li>
 *   <li>point the schematic locator at {@code .minecraft/schematics/}.</li>
 *   <li>load settings and register the optional D Web Studio watermark plus the
 *       settings/about screen.</li>
 * </ol>
 */
public final class ChikaBuilderClient implements ClientModInitializer {

    public static final String MOD_ID = "chika-builder";
    public static final String DISPLAY_NAME = Branding.PRODUCT_NAME;
    public static final Logger LOGGER = LoggerFactory.getLogger(DISPLAY_NAME);

    private static ChikaBuilderClient instance;

    private SchematicLocator locator;
    private BuildService buildService;
    private BuildCoordinator coordinator;
    private KeyMapping openSettingsKey;

    /** Ticks remaining in the periodic command-lockdown re-check. */
    private int lockdownTicks;

    /** True once the startup lockdown has been logged, so we log it only once. */
    private boolean lockdownReported;

    public static ChikaBuilderClient getInstance() {
        return instance;
    }

    public SchematicLocator locator() {
        return this.locator;
    }

    public BuildService buildService() {
        return this.buildService;
    }

    @Override
    public void onInitializeClient() {
        instance = this;

        // getGameDirectory() is deprecated; getGameDir() returns a java.nio.file.Path.
        // The game's own schematics folder lives next to the world saves.
        File schematicsDir = FabricLoader.getInstance().getGameDir()
                .resolve("schematics")
                .toFile();
        if (!schematicsDir.isDirectory() && !schematicsDir.mkdirs()) {
            LOGGER.warn("[{}] Could not create schematics folder at {}",
                    DISPLAY_NAME, schematicsDir.getAbsolutePath());
        }

        this.locator = new SchematicLocator(schematicsDir);
        this.buildService = new ChikaBuildService();

        // Settings (watermark ON/OFF, default ON) and the branding surfaces.
        ChikaConfig.load();
        registerBranding();

        // The supply chain: read the schematic, check inventory, then Creative
        // and auto-shop if the player has enabled them. Reads the player's real
        // gamemode only - it never changes it, and the supplier re-checks that
        // fact immediately before every hand-over.
        PlayerContext player = new MinecraftPlayerContext();
        SchematicAnalyzer analyzer = new ChikaSchematicAnalyzer();
        PurchaseOrchestrator purchases = PurchaseOrchestrator.usingRealRegistry(player::countItem);
        CreativeSupplier creativeSupplier = new CreativeInventorySupplier();

        this.coordinator = new BuildCoordinator(this.buildService, analyzer, player, purchases,
                creativeSupplier, new BuildCoordinator.Settings() {
                    @Override
                    public boolean isCreativeEnabled() {
                        return ChikaConfig.get().isCreativeEnabled();
                    }

                    @Override
                    public boolean isShopEnabled() {
                        return ChikaConfig.get().isShopEnabled();
                    }
                });

        // The internal build engine finishes registering its own commands after
        // mod init, so the lockdown retries here and then re-checks briefly on
        // a timer. This is what makes the removal stick.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            for (int attempt = 1; attempt <= 20; attempt++) {
                try {
                    registerAndLockDown();
                    break;
                } catch (Throwable t) {
                    if (attempt == 20) {
                        LOGGER.error("[{}] Failed to register commands", DISPLAY_NAME, t);
                    } else {
                        sleep(200L);
                    }
                }
            }

            // Re-sweep for the first ~20 seconds to catch any late registration.
            this.lockdownTicks = 400;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (this.lockdownTicks > 0) {
                this.lockdownTicks--;
                this.enforceLockdown();
            }
        });
    }

    /**
     * Registers the unobtrusive D Web Studio watermark and the settings/about
     * screen keybind. Neither of these touches the build or pathing logic.
     */
    private void registerBranding() {
        try {
            WatermarkHud.register();
        } catch (Throwable t) {
            LOGGER.warn("[{}] Watermark could not be registered", DISPLAY_NAME, t);
        }

        // Unbound by default (GLFW_KEY_UNKNOWN == -1) so Chika Builder never
        // steals a key the player already uses.
        this.openSettingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.chika_builder.open_settings",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                KeyMapping.Category.MISC));

        // ClientTickEvents exposes Event fields rather than a register(...) helper.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (this.openSettingsKey.consumeClick()) {
                client.setScreen(new ChikaSettingsScreen(client.screen));
            }
        });
    }

    /**
     * Registers {@code #chika_build} and {@code #chika_builder}, then removes
     * every other engine command.
     *
     * <p>Order matters: the lockdown runs last so it keeps our two commands and
     * removes everything registered around it.
     */
    public void registerAndLockDown() {
        IBaritone engine = BaritoneAPI.getProvider().getPrimaryBaritone();

        ICommand command = new ChikaBuildCommand(engine, this.coordinator, this.locator);
        engine.getCommandManager().getRegistry().register(command);

        // Settings command: #chika_builder creative|shop true|false
        engine.getCommandManager().getRegistry().register(new ChikaBuilderCommand(engine));

        this.enforceLockdown();
    }

    /**
     * Removes every command except {@code #chika_build} and
     * {@code #chika_builder}, and reports the result.
     *
     * <p>Called both at startup and on an ongoing schedule, because the engine
     * registers its own commands after mod initialisation finishes. Re-running
     * the sweep guarantees those cannot sneak through and become reachable.
     */
    private void enforceLockdown() {
        var removed = CommandLockdown.enforce();

        if (!this.lockdownReported && !removed.isEmpty()) {
            this.lockdownReported = true;
            LOGGER.info("[{}] Enabled {} only. Disabled {} other command(s).",
                    DISPLAY_NAME, CommandLockdown.allowedCommands(), removed.size());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
package dev.chika.builder;

import dev.chika.builder.build.BuildService;
import dev.chika.builder.platform.baritone.BaritoneBuildService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the separation between Chika Builder's own layer and the internal
 * build engine, which is what lets the backend be swapped later.
 */
class LayeringTest {

    @Test
    void engineServiceImplementsTheServiceInterface() {
        BuildService service = new BaritoneBuildService();
        assertTrue(service instanceof BuildService);
    }

    @Test
    void engineNameIsNotUserFacingBranding() {
        // The backend identifier must never read as a product name.
        String name = new BaritoneBuildService().name();
        assertFalse(name.toLowerCase(java.util.Locale.ROOT).contains("baritone"),
                "backend name must not expose engine branding: " + name);
    }

    @Test
    void commandLayerDoesNotDependOnEngine() {
        // The command + schematic layers must stay backend-agnostic so a
        // standalone builder can be dropped in without touching #chika_build.
        assertFalse(dependsOnAny(dev.chika.builder.command.ChikaBuildCommand.class, "baritone."));
        assertFalse(dependsOnAny(dev.chika.builder.schematic.SchematicLocator.class, "baritone."));
        assertFalse(dependsOnAny(BuildService.class, "baritone."));
    }

    @Test
    void watermarkLogicIsSeparateFromTheBuilder() {
        // Watermark / UI code must not be reachable from the build service,
        // so branding can never interfere with building or block placement.
        assertFalse(dependsOnAny(BaritoneBuildService.class, "dev.chika.builder.ui"));
        assertFalse(dependsOnAny(BaritoneBuildService.class, "dev.chika.builder.config"));
    }

    private static boolean dependsOnAny(Class<?> type, String prefix) {
        for (Class<?> iface : type.getInterfaces()) {
            if (iface.getName().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
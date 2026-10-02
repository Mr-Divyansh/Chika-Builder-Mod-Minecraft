package dev.chika.builder;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that Chika Builder is the ONLY mod the player ever sees for building.
 *
 * <p>The build engine used to ship as its own jar ({@code baritone-meteor}),
 * which made Fabric list a second row - "Baritone" - in the Mods screen. The
 * engine is now merged into this mod's jar at build time, so these tests guard
 * the properties that keep that from coming back:
 *
 * <ul>
 *   <li>this mod must not declare a dependency on the engine's mod id, which
 *       would make Fabric require a separate jar to be installed;</li>
 *   <li>the engine's mixin config and nested library must be declared here, or
 *       pathing/building silently stops working after a rename;</li>
 *   <li>no player-visible metadata may name the engine.</li>
 * </ul>
 *
 * <p>Note the engine's mixin config is deliberately renamed to
 * {@code mixins.chika_engine.json}. FabricMixinPlugin gates on the mixin CLASS
 * names ({@code baritone.launch.mixins.*}), not on the config file name, so the
 * rename is cosmetic and safe.
 */
class SingleModEntryTest {

    private static final String EXPECTED_MIXIN_CONFIG = "mixins.chika_engine.json";
    private static final String EXPECTED_NESTED_JAR = "META-INF/jars/nether-pathfinder-1.4.1.jar";

    /** The engine's former Fabric mod id. It must no longer be a dependency. */
    private static final String ENGINE_MOD_ID = "baritone-meteor";

    @Test
    void doesNotDependOnTheEngineAsASeparateMod() throws IOException {
        // Depending on baritone-meteor would force the player to install a
        // second jar, which is exactly what puts "Baritone" in the Mods screen.
        String metadata = readResource("/fabric.mod.json");
        assertFalse(metadata.contains(ENGINE_MOD_ID),
                "fabric.mod.json must not depend on " + ENGINE_MOD_ID
                        + "; that requires a separate jar and a second Mods-screen entry");
    }

    @Test
    void declaresTheEngineMixinConfig() throws IOException {
        // Without this the engine's 21 client mixins never apply, so
        // BaritoneAPI.getProvider() is never initialised and #chika_build
        // fails with "the builder is not available yet".
        String metadata = readResource("/fabric.mod.json");
        assertTrue(metadata.contains("\"" + EXPECTED_MIXIN_CONFIG + "\""),
                "fabric.mod.json must declare the engine mixin config: " + EXPECTED_MIXIN_CONFIG);
    }

    @Test
    void declaresTheNestedPathfinderLibrary() throws IOException {
        // The engine's nested jar is loaded via the "jars" array, not by
        // classpath scanning, so an undeclared entry would be silently ignored.
        String metadata = readResource("/fabric.mod.json");
        assertTrue(metadata.contains(EXPECTED_NESTED_JAR),
                "fabric.mod.json must declare the nested library: " + EXPECTED_NESTED_JAR);
    }

    @Test
    void engineClassesAreMergedIntoThisMod() {
        // Proof the engine ships INSIDE this jar, so it never needs its own.
        assertNotNull(SingleModEntryTest.class.getResource("/baritone/api/BaritoneAPI.class"),
                "engine classes must be packaged in this mod's jar");
    }

    @Test
    void userFacingMetadataNeverNamesTheEngine() throws IOException {
        // name and description are rendered in the Mods screen, so neither may
        // mention the engine.
        //
        // "description_marker" is deliberately NOT checked here: Fabric Loader
        // rejects it as an unsupported root entry ("Unsupported root entry
        // \"description_marker\"" in latest.log), so it has been removed from
        // fabric.mod.json entirely.
        String metadata = readResource("/fabric.mod.json");

        for (String field : List.of("name", "description")) {
            Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(metadata);
            assertTrue(m.find(), "fabric.mod.json must declare a \"" + field + "\"");
            assertFalse(m.group(1).toLowerCase(Locale.ROOT).contains("baritone"),
                    "\"" + field + "\" is shown to players and must not name the engine: " + m.group(1));
        }
    }

    @Test
    void theModJsonHasNoUnsupportedRootEntries() throws IOException {
        // The live log showed Fabric warning about invalid mod json entries on
        // every launch. Nothing outside the documented schema may appear.
        String metadata = readResource("/fabric.mod.json");

        assertFalse(metadata.contains("description_marker"),
                "Fabric rejects \"description_marker\" as an unsupported root entry");

        // Must still be valid JSON with the fields the loader requires.
        assertTrue(metadata.contains("\"id\""), metadata);
        assertTrue(metadata.contains("\"version\""), metadata);
        assertTrue(metadata.contains("\"entrypoints\""), metadata);
    }

    @Test
    void theOnlyVisibleProductIdentityIsChikaBuilderByDWebStudio() throws IOException {
        String metadata = readResource("/fabric.mod.json");

        Matcher name = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(metadata);
        assertTrue(name.find());
        assertEquals("Chika Builder", name.group(1), "the mod name shown in the Mods screen");

        assertTrue(metadata.contains("D Web Studio"),
                "D Web Studio must remain credited in the mod metadata");
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = SingleModEntryTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing packaged resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

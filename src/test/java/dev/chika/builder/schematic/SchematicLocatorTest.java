package dev.chika.builder.schematic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests schematic discovery against the {@code .minecraft/schematics/} folder.
 *
 * <p>This is the first step of {@code #chika_build}, so these assertions pin
 * how a typed name becomes a real file: forgiving where a player expects
 * (extension optional, case ignored) and strict where safety matters (a name
 * can never escape the schematics directory).
 */
class SchematicLocatorTest {

    @TempDir
    Path schematics;

    private SchematicLocator locator() {
        return new SchematicLocator(this.schematics.toFile());
    }

    private File schematic(String name, String content) throws IOException {
        File file = new File(this.schematics.toFile(), name);
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        return file;
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    @Test
    void directoryIsTheSchematicsFolder() {
        assertEquals(this.schematics.toFile(), locator().directory());
    }

    @Test
    void findsAnExactFileName() throws IOException {
        File written = schematic("castle.schematic", "schematic-data");

        File found = locator().find("castle.schematic");

        assertNotNull(found, "an existing schematic must be found");
        assertEquals("castle.schematic", found.getName());
    }

    @Test
    void findsAReadableFileThatActuallyExists() throws IOException {
        schematic("house.schematic", "more schematic data");

        File found = locator().find("house.schematic");

        assertNotNull(found);
        assertTrue(found.isFile(), "the discovered schematic must be a real file");
        assertTrue(found.length() > 0, "the discovered schematic must have contents to load");
    }

    @Test
    void extensionMayBeOmitted() throws IOException {
        schematic("tower.schematic", "x");

        assertNotNull(locator().find("tower"), ".schematic must be optional");
        assertNotNull(locator().find("tower.schematic"));
    }

    @Test
    void litematicFilesAreDiscoveredTheSameWay() throws IOException {
        schematic("farm.litematic", "x");

        assertNotNull(locator().find("farm.litematic"));
        assertNotNull(locator().find("farm"), "the extension may be omitted for .litematic too");
    }

    @Test
    void matchingIgnoresCase() throws IOException {
        schematic("Castle.Schematic", "x");

        assertNotNull(locator().find("castle.schematic"), "file names are matched case-insensitively");
        assertNotNull(locator().find("CASTLE"));
        assertNotNull(locator().find("castle"));
    }

    @Test
    void unknownOrEmptyNamesResolveToNothing() throws IOException {
        schematic("castle.schematic", "x");

        assertNull(locator().find("fortress"), "a name that is not in the folder must not resolve");
        assertNull(locator().find(""), "an empty name must not resolve");
        assertNull(locator().find("   "), "a blank name must not resolve");
        assertNull(locator().find(null), "a null name must not resolve");
    }

    @Test
    void aNameCanNeverEscapeTheSchematicsFolder() throws IOException {
        schematic("castle.schematic", "x");

        assertNull(locator().find("../secrets.schematic"), "'..' must be rejected");
        assertNull(locator().find("..\\secrets.schematic"), "backslash traversal must be rejected");
        assertNull(locator().find("sub/castle.schematic"), "sub-paths must be rejected");
        assertNull(locator().find("sub\\castle.schematic"), "sub-paths must be rejected");
    }

    @Test
    void aMissingFolderResolvesToNothing() {
        SchematicLocator missing = new SchematicLocator(
                this.schematics.resolve("does-not-exist").toFile());

        assertNull(missing.find("castle.schematic"));
        assertTrue(missing.list().isEmpty(), "an unreadable folder lists nothing instead of failing");
        assertTrue(missing.suggest("c").isEmpty());
    }

    // ------------------------------------------------------------------
    // Listing / completion
    // ------------------------------------------------------------------

    @Test
    void listsOnlySchematicFilesSortedAlphabetically() throws IOException {
        schematic("tower.schematic", "x");
        schematic("castle.schematic", "x");
        schematic("garden.litematic", "x");
        schematic("readme.txt", "not a schematic");

        List<String> names = locator().list();

        assertEquals(List.of("castle.schematic", "garden.litematic", "tower.schematic"), names,
                "only schematics are offered, and they are sorted");
        assertFalse(names.contains("readme.txt"), "non-schematic files must not be offered");
    }

    @Test
    void suggestionsFilterOnTheTypedPrefix() throws IOException {
        schematic("castle.schematic", "x");
        schematic("cottage.schematic", "x");
        schematic("tower.schematic", "x");

        List<String> names = locator().suggest("c");

        assertEquals(List.of("castle.schematic", "cottage.schematic"), names);
        assertTrue(locator().suggest("z").isEmpty(), "no match means no suggestions");
        assertTrue(locator().suggest("").size() == 3, "an empty prefix suggests everything");
    }
}

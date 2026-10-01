package dev.chika.builder.schematic;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Resolves schematic file names typed by the player against the
 * {@code .minecraft/schematics/} folder.
 *
 * <p>The lookup is deliberately forgiving: the extension may be omitted, the
 * {@code .schematic} suffix is optional, and matching is case-insensitive. This
 * mirrors how players actually expect a "just type the name" command to behave.
 */
public final class SchematicLocator {

    /** Extensions we consider a schematic, in priority order. */
    private static final List<String> EXTENSIONS = List.of(".schematic", ".litematic");

    private final File directory;

    public SchematicLocator(File directory) {
        this.directory = directory;
    }

    public File directory() {
        return this.directory;
    }

    /**
     * Finds a schematic by user supplied name.
     *
     * @param input raw text typed after the command, e.g. {@code house.schematic}
     * @return the resolved file, or {@code null} when nothing matches
     */
    public File find(String input) {
        if (input == null) {
            return null;
        }

        String name = input.trim();
        if (name.isEmpty()) {
            return null;
        }

        // Never allow the name to escape the schematics directory.
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }

        File exact = new File(this.directory, name);
        if (exact.isFile()) {
            return exact;
        }

        for (String extension : EXTENSIONS) {
            File withExtension = new File(this.directory, name + extension);
            if (withExtension.isFile()) {
                return withExtension;
            }
        }

        // Fall back to a case-insensitive scan of the folder.
        File[] children = this.directory.listFiles();
        if (children == null) {
            return null;
        }

        for (File child : children) {
            if (!child.isFile()) {
                continue;
            }
            String childName = child.getName();
            if (childName.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return child;
            }
            for (String extension : EXTENSIONS) {
                String stem = childName.substring(0, childName.length() - extension.length());
                if (childName.toLowerCase(Locale.ROOT).endsWith(extension)
                        && stem.equalsIgnoreCase(name)) {
                    return child;
                }
            }
        }

        return null;
    }

    /** Lists available schematic file names, for tab completion. */
    public List<String> list() {
        File[] children = this.directory.listFiles();
        if (children == null) {
            return Collections.emptyList();
        }

        List<String> names = new ArrayList<>();
        for (File child : children) {
            String lower = child.getName().toLowerCase(Locale.ROOT);
            if (child.isFile() && EXTENSIONS.stream().anyMatch(lower::endsWith)) {
                names.add(child.getName());
            }
        }

        Collections.sort(names);
        return names;
    }

    /** Candidate completions for the text typed so far. */
    public List<String> suggest(String input) {
        String prefix = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String name : list()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(name);
            }
        }
        return out;
    }

    /** Stream of available names, for callers that prefer streams. */
    public Stream<String> names() {
        return list().stream();
    }
}
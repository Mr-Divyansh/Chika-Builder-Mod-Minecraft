package dev.chika.builder.build.material;

import dev.chika.builder.build.BuildService;

import java.io.File;
import java.util.List;

/**
 * Turns a schematic file into the list of materials it needs.
 *
 * <p>Kept separate from the planner so the planner stays pure and testable, and
 * so a different schematic format can be supported later without touching the
 * supply logic.
 */
public interface SchematicAnalyzer {

    /**
     * Analyses a schematic and reports what it needs.
     *
     * <p>Blocks that are already correct in the world at {@code origin} are
     * counted as {@code alreadyPlaced}, so a resumed build only asks for what is
     * genuinely still missing.
     *
     * @param schematic the schematic file
     * @param origin    world position the schematic's (0,0,0) corner sits at
     * @return one {@link MaterialNeed} per distinct non-air block type
     * @throws SchematicAnalysisException if the file cannot be read or parsed
     */
    List<MaterialNeed> analyze(File schematic, BuildService.Origin origin)
            throws SchematicAnalysisException;

    /** Raised when a schematic cannot be parsed. */
    class SchematicAnalysisException extends Exception {

        private static final long serialVersionUID = 1L;

        public SchematicAnalysisException(String message) {
            super(message);
        }

        public SchematicAnalysisException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

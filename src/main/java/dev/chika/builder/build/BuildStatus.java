package dev.chika.builder.build;

/**
 * Outcome of a build request, including why it may not proceed.
 *
 * <p>Separates "the build cannot start" from "the build is paused part way",
 * because the player's remedy is different in each case.
 */
public enum BuildStatus {

    /** The build started (or resumed) and is running. */
    STARTED,

    /** The build could not start: materials or settings are missing. */
    PAUSED,

    /** Nothing was requested, e.g. the schematic file is missing. */
    REJECTED
}

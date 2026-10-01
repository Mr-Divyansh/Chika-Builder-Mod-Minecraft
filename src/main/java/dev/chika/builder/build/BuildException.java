package dev.chika.builder.build;

/** Raised when a schematic cannot be loaded or a build cannot be started. */
public class BuildException extends Exception {

    private static final long serialVersionUID = 1L;

    public BuildException(String message) {
        super(message);
    }

    public BuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
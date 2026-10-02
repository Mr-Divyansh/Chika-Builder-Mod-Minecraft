package dev.chika.builder.build.material;

/**
 * The player's block position, used to tell "standing still" from "moving".
 *
 * <p>Deliberately a plain value type with no engine or Minecraft types, so the
 * movement watchdog can compare positions in a unit test with no game running.
 */
public record PlayerPosition(int x, int y, int z) {

    @Override
    public String toString() {
        return "(" + this.x + ", " + this.y + ", " + this.z + ")";
    }
}
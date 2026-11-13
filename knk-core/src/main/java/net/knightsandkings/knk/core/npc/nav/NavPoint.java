package net.knightsandkings.knk.core.npc.nav;

import java.util.Objects;

/**
 * A position an NPC stands at (feet level) in a world, without engine types.
 *
 * @param world the world's name
 */
public record NavPoint(String world, double x, double y, double z) {

    public NavPoint {
        Objects.requireNonNull(world, "world");
    }

    /** Horizontal distance to {@code other} (same world assumed). */
    public double horizontalDistance(NavPoint other) {
        return Math.hypot(other.x - x, other.z - z);
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }
}

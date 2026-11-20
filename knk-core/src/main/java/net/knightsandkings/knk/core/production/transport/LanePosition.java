package net.knightsandkings.knk.core.production.transport;

/** A structure's position as the lane list sends it ({@code world}, {@code x}, {@code y}, {@code z} of its Location). */
public record LanePosition(String world, double x, double y, double z) {

    /** Straight 3D distance in blocks; only meaningful in the same world. */
    public double distanceTo(LanePosition other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public boolean sameWorld(LanePosition other) {
        return other != null && world != null && world.equalsIgnoreCase(other.world);
    }
}

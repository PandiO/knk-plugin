package net.knightsandkings.knk.core.survival;

/** A world position without Bukkit: block or entity coordinates in one world (the caller keeps the world). */
public record SurvivalPoint(double x, double y, double z) {

    public double distanceTo(SurvivalPoint other) {
        return Math.sqrt(distanceSquaredTo(other));
    }

    public double distanceSquaredTo(SurvivalPoint other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}

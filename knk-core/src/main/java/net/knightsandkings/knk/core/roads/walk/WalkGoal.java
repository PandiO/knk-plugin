package net.knightsandkings.knk.core.roads.walk;

/**
 * When the walk search has arrived (KNG-51 {@code LAST_MILE_PATHFINDING.md} §5): a predicate over a
 * cell's floor position (block centre x/z, floor y; feet at {@code floorY + 1}), so a region
 * destination can pass {@code RegionShape::containsFloor} unchanged and a point destination uses
 * {@link #within}.
 */
@FunctionalInterface
public interface WalkGoal {

    /** Whether a cell with this floor position counts as arrived. */
    boolean reached(double x, double floorY, double z);

    /** Arrived within {@code distance} blocks (3D, floor positions) of a floor point — navigation's {@code arriveDistance}. */
    static WalkGoal within(double tx, double tFloorY, double tz, double distance) {
        if (!(distance >= 0.0)) {
            throw new IllegalArgumentException("distance must be >= 0: " + distance);
        }
        double max = distance * distance;
        return (x, floorY, z) -> {
            double dx = x - tx;
            double dy = floorY - tFloorY;
            double dz = z - tz;
            return dx * dx + dy * dy + dz * dz <= max;
        };
    }
}

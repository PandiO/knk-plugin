package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds the nearest point of the network to a player or destination (DESIGN §6.2 step 2, §5.2):
 * 3D distance with the height difference multiplied by {@code snap-vertical-weight}, so a player on
 * a bridge snaps to the bridge and not to the road 8 blocks below.
 *
 * <p><b>Coordinates (Phase 2d decision):</b> {@link #snap} takes the player's <b>feet</b> position
 * (what {@code Location#getY()} gives for a standing player) and compares it with the polyline's
 * floor y + 1 (Phase 2c decision 1: geometry points are floor blocks, feet are one above). A
 * destination given as a floor block goes through {@link #snapFloor}. Bukkit-free.
 */
public final class Snapper {

    private final RoadNetworkSnapshot snapshot;
    private final RouterParameters parameters;

    public Snapper(RoadNetworkSnapshot snapshot, RouterParameters parameters) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
    }

    /**
     * Nearest network point to a player standing with feet at {@code (x, y, z)}, within the
     * configured max distance; empty when no road is close enough (the player is "too far from a
     * road").
     */
    public Optional<SnapPoint> snap(double feetX, double feetY, double feetZ) {
        return snapFloor(feetX, feetY - 1, feetZ);
    }

    /** Like {@link #snap} for a floor-block position (a node, a Location's block, a stored seed). */
    public Optional<SnapPoint> snapFloor(double x, double floorY, double z) {
        return snap(snapshot, x, floorY, z, parameters.maxSnapDistance(), parameters.snapVerticalWeight());
    }

    /**
     * Like {@link #snapFloor} on the edges of the network components {@code components} accepts only (live
     * test 2026-10-08, N12: the nearest road was a short stretch that joins nothing, while the network the
     * destination is on lay a few blocks further).
     */
    public Optional<SnapPoint> snapFloor(double x, double floorY, double z, java.util.function.IntPredicate components) {
        return snap(snapshot, x, floorY, z, parameters.maxSnapDistance(), parameters.snapVerticalWeight(),
            edgeIndex -> components.test(snapshot.componentOf(snapshot.edgeAt(edgeIndex))));
    }

    /**
     * The plan's signature: nearest point of {@code snapshot} to the <b>floor</b> position
     * {@code (x, y, z)} within {@code maxDistance} (weighted), height weighted × {@code verticalWeight}.
     */
    public static Optional<SnapPoint> snap(RoadNetworkSnapshot snapshot, double x, double y, double z,
                                           double maxDistance, double verticalWeight) {
        return snap(snapshot, x, y, z, maxDistance, verticalWeight, null);
    }

    /** As above on the edges {@code edgeFilter} accepts (by edge index; null = all). */
    public static Optional<SnapPoint> snap(RoadNetworkSnapshot snapshot, double x, double y, double z,
                                           double maxDistance, double verticalWeight, java.util.function.IntPredicate edgeFilter) {
        return snap(snapshot, x, y, z, maxDistance, verticalWeight, edgeFilter, false);
    }

    /**
     * Like {@link #snap(RoadNetworkSnapshot, double, double, double, double, double, java.util.function.IntPredicate)},
     * but {@code maxDistance} is plain 3D (KNG-75): the height weight only <i>ranks</i> the roads within reach, so a
     * player beside a bridge still snaps to the bridge, while a road {@code maxDistance} blocks away is not out of
     * reach because it lies below. The snap point's distance is the plain one.
     */
    public static Optional<SnapPoint> snapRanked(RoadNetworkSnapshot snapshot, double x, double y, double z,
                                                 double maxDistance, double verticalWeight,
                                                 java.util.function.IntPredicate edgeFilter) {
        return snap(snapshot, x, y, z, maxDistance, verticalWeight, edgeFilter, true);
    }

    private static Optional<SnapPoint> snap(RoadNetworkSnapshot snapshot, double x, double y, double z, double maxDistance,
                                            double verticalWeight, java.util.function.IntPredicate edgeFilter,
                                            boolean plainLimit) {
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        double w = verticalWeight;
        double[] best = {Double.POSITIVE_INFINITY};
        int[] bestRef = {-1, -1};
        double[] bestT = {0};
        snapshot.segmentIndex().forEachNear(x, z, maxDistance, (edgeIndex, segmentIndex) -> {
            if (edgeFilter != null && !edgeFilter.test(edgeIndex)) {
                return;
            }
            List<int[]> pts = snapshot.polylineAt(edgeIndex).points();
            int[] a = pts.get(segmentIndex);
            int[] b = pts.get(segmentIndex + 1);
            // closest point on the segment in the weighted metric (y scaled by w)
            double ax = a[0], ay = a[1] * w, az = a[2];
            double dx = b[0] - ax, dy = b[1] * w - ay, dz = b[2] - az;
            double len2 = dx * dx + dy * dy + dz * dz;
            double t = 0;
            if (len2 > 0) {
                t = ((x - ax) * dx + (y * w - ay) * dy + (z - az) * dz) / len2;
                t = Math.max(0, Math.min(1, t));
            }
            double cx = ax + dx * t - x;
            double cy = ay + dy * t - y * w;
            double cz = az + dz * t - z;
            double d2 = cx * cx + cy * cy + cz * cz;
            if (plainLimit && plainDistance(a, b, t, x, y, z) > maxDistance) {
                return;
            }
            if (d2 < best[0] || (d2 == best[0] && bestRef[0] >= 0 && lowerRef(edgeIndex, segmentIndex, bestRef))) {
                best[0] = d2;
                bestRef[0] = edgeIndex;
                bestRef[1] = segmentIndex;
                bestT[0] = t;
            }
        });
        if (bestRef[0] < 0) {
            return Optional.empty();
        }
        RoadEdge edge = snapshot.edgeAt(bestRef[0]);
        EdgePolyline polyline = snapshot.polylineAt(bestRef[0]);
        int seg = bestRef[1];
        double t = bestT[0];
        double distance = plainLimit
            ? plainDistance(polyline.points().get(seg), polyline.points().get(seg + 1), t, x, y, z)
            : Math.sqrt(best[0]);
        if (distance > maxDistance) {
            return Optional.empty();
        }
        double[] point = EdgePolyline.interpolate(polyline.points().get(seg), polyline.points().get(seg + 1), t);
        return Optional.of(new SnapPoint(edge.id(), seg, t, point, distance, polyline.along(seg, t)));
    }

    /** Plain 3D distance from {@code (x, y, z)} to the point at {@code t} on the segment {@code a}-{@code b}. */
    private static double plainDistance(int[] a, int[] b, double t, double x, double y, double z) {
        double px = a[0] + (b[0] - a[0]) * t - x;
        double py = a[1] + (b[1] - a[1]) * t - y;
        double pz = a[2] + (b[2] - a[2]) * t - z;
        return Math.sqrt(px * px + py * py + pz * pz);
    }

    /** Tie-break: the lower edge index, then the lower segment (deterministic across bucket order). */
    private static boolean lowerRef(int edgeIndex, int segmentIndex, int[] bestRef) {
        return edgeIndex < bestRef[0] || (edgeIndex == bestRef[0] && segmentIndex < bestRef[1]);
    }
}

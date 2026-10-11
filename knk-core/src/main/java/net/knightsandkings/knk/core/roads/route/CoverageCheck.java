package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.roads.survey.SurveySample;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Survey review helper (plan 2d, used by Phase 3's survey review): which breadcrumbs of a walk
 * have no network edge within {@code maxDistance} blocks - the places the builder missed - with
 * the floor material the admin walked on there. Breadcrumbs are {@link SurveySample}s (floor-y
 * convention: {@code y} is the floor under the feet, like the network's points), so the distance is
 * plain 3D with no vertical weighting.
 */
public final class CoverageCheck {

    /** DESIGN/plan default: a breadcrumb farther than 2 blocks from any edge is a miss. */
    public static final double DEFAULT_MAX_DISTANCE = 2;

    /**
     * A breadcrumb without a nearby edge.
     *
     * @param x        breadcrumb x
     * @param y        floor y
     * @param z        breadcrumb z
     * @param floor    the floor material seen there
     * @param distance distance to the nearest edge, or {@code +∞} when none is within the search
     *                 radius (the snapshot is empty or the road is far away)
     */
    public record Miss(int x, int y, int z, String floor, double distance) {
    }

    private CoverageCheck() {
    }

    public static List<Miss> misses(List<SurveySample> breadcrumbs, RoadNetworkSnapshot snapshot) {
        return misses(breadcrumbs, snapshot, DEFAULT_MAX_DISTANCE);
    }

    /** The breadcrumbs with no edge segment within {@code maxDistance} (3D), in walk order. */
    public static List<Miss> misses(List<SurveySample> breadcrumbs, RoadNetworkSnapshot snapshot, double maxDistance) {
        List<Miss> misses = new ArrayList<>();
        for (SurveySample crumb : breadcrumbs) {
            Optional<SnapPoint> near = Snapper.snap(snapshot, crumb.x(), crumb.y(), crumb.z(), maxDistance, 1);
            if (near.isEmpty()) {
                misses.add(new Miss(crumb.x(), crumb.y(), crumb.z(), crumb.floor(), Double.POSITIVE_INFINITY));
            }
        }
        return misses;
    }

    /** Share of breadcrumbs covered (1.0 for an empty walk). */
    public static double coverage(List<SurveySample> breadcrumbs, RoadNetworkSnapshot snapshot, double maxDistance) {
        if (breadcrumbs.isEmpty()) {
            return 1.0;
        }
        return 1.0 - (double) misses(breadcrumbs, snapshot, maxDistance).size() / breadcrumbs.size();
    }
}

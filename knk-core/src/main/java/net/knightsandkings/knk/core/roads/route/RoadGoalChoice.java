package net.knightsandkings.knk.core.roads.route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * A point destination's road goal by walk reachability (off-road fix link F2, PandiO/knk-workspace#780 option (b)).
 * Today the goal is the road point nearest the destination in a straight line ({@link Snapper#snapFloor}); for a
 * destination inside a building above or below a road that point can be a dead end from which the destination is a long
 * walk away (the keep storage hall: the stair house's lower landing, about 290 blocks of walking), or a point past the
 * spot where the walk leaves the road (#771 item 4: the road part overshoots and the walk trail comes back).
 *
 * <p>This class is the pure part: the bounded candidate set ({@link #candidates}) and the choice among the candidates a
 * walk search reached ({@link #best}): the least road length plus walk length wins. The runtime searches each candidate
 * off the main thread within the shipped walk limits and routes to each reachable one. Bukkit-free.
 */
public final class RoadGoalChoice {

    /** Candidates closer than this to an earlier (nearer) candidate are the same place (an edge's end is the next one's start). */
    public static final double SAME_PLACE = 1.0;

    private RoadGoalChoice() {
    }

    /**
     * The road points to try, nearest first: {@code nearest} (today's goal) first, then the point of each other road edge
     * nearest to the destination's floor position {@code (x, floorY, z)} within {@code maxDistance} (plain 3D), one per
     * edge, skipping points within {@link #SAME_PLACE} of an earlier one; at most {@code max} in all. Deterministic: ties
     * go to the lower edge index.
     *
     * @param snapshot    the road network
     * @param nearest     today's goal ({@link Snapper#snapFloor}); null when there is none
     * @param maxDistance the candidates' reach from the destination (the runtime passes {@code max-snap-distance})
     * @param max         the most candidates, today's goal included (at least 1)
     */
    public static List<SnapPoint> candidates(RoadNetworkSnapshot snapshot, double x, double floorY, double z,
                                             double maxDistance, int max, SnapPoint nearest) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (max < 1) {
            throw new IllegalArgumentException("max must be at least 1");
        }
        List<SnapPoint> out = new ArrayList<>();
        if (nearest != null) {
            out.add(nearest);
        }
        if (snapshot.isEmpty() || out.size() >= max) {
            return out;
        }
        TreeSet<Integer> edgeIndexes = new TreeSet<>();
        snapshot.segmentIndex().forEachNear(x, z, maxDistance, (edgeIndex, segmentIndex) -> edgeIndexes.add(edgeIndex));
        List<SnapPoint> perEdge = new ArrayList<>();
        for (int edgeIndex : edgeIndexes) {
            Snapper.snapRanked(snapshot, x, floorY, z, maxDistance, 1, i -> i == edgeIndex).ifPresent(perEdge::add);
        }
        perEdge.sort(Comparator.comparingDouble(SnapPoint::distance)); // stable: equal distances keep the edge order
        for (SnapPoint p : perEdge) {
            if (out.size() >= max) {
                break;
            }
            if (out.stream().noneMatch(q -> q.distanceTo(p) < SAME_PLACE)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * One candidate a walk search reached: its road goal, the road length of the route to it, the walk path's length
     * from it to the destination.
     */
    public record Option(SnapPoint goal, double roadLength, double walkLength, List<double[]> walkPath) {
        public Option {
            Objects.requireNonNull(goal, "goal");
            walkPath = walkPath == null ? List.of() : List.copyOf(walkPath);
        }

        public Option(SnapPoint goal, double roadLength, double walkLength) {
            this(goal, roadLength, walkLength, List.of());
        }

        /** Road plus walk, in walked blocks. */
        public double total() {
            return roadLength + walkLength;
        }
    }

    /**
     * The option with the least {@link Option#total()}; a tie (within a hundredth of a block) goes to the earlier one,
     * so with the candidates in {@link #candidates}' order to the nearer road point. Empty when there is none.
     */
    public static Optional<Option> best(List<Option> options) {
        Option best = null;
        for (Option o : options) {
            if (best == null || o.total() < best.total() - 0.01) {
                best = o;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Whether two road points are the same goal: the same edge, within {@link #SAME_PLACE} along it. */
    public static boolean sameGoal(SnapPoint a, SnapPoint b) {
        return a.edgeId() == b.edgeId() && Math.abs(a.along() - b.along()) < SAME_PLACE;
    }
}

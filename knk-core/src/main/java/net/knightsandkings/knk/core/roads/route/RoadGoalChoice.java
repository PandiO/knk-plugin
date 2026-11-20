package net.knightsandkings.knk.core.roads.route;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 *
 * <p>Fix link F3 (live smoke test of 2026-11-19, #780, #771 items 4 and 5) adds, without changing the methods above: the
 * road's nodes onward from today's goal as candidates ({@link #candidatesAlongRoad}), so the road can carry the route
 * further than the per-edge nearest points, and a choice that weighs the walk above the road ({@link #best(List, double)}):
 * the walk starts as late as the road usefully carries the route.
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
     * F3: {@link #candidates} plus the road's nodes onward from {@code nearest} (today's goal): the nodes reached from its
     * edge's two ends along the network without leaving {@code maxDistance} of the destination's floor position. The
     * road's further points (the next nodes past today's goal, e.g. the end of the street it lies on) can then be chosen
     * though no edge's nearest point is there. Today's goal first, then every other point nearest first, skipping points
     * within {@link #SAME_PLACE} of an earlier one; at most {@code max}. Without {@code nearest}: {@link #candidates}.
     */
    public static List<SnapPoint> candidatesAlongRoad(RoadNetworkSnapshot snapshot, double x, double floorY, double z,
                                                      double maxDistance, int max, SnapPoint nearest) {
        List<SnapPoint> perEdge = candidates(snapshot, x, floorY, z, maxDistance, 32, nearest);
        if (nearest == null || snapshot.isEmpty()) {
            return perEdge.size() > max ? List.copyOf(perEdge.subList(0, max)) : perEdge;
        }
        List<SnapPoint> pool = new ArrayList<>(perEdge.subList(1, perEdge.size()));
        pool.addAll(roadOnward(snapshot, nearest, x, floorY, z, maxDistance));
        pool.sort(Comparator.comparingDouble(p -> distance(p, x, floorY, z))); // stable: per-edge points first on a tie
        List<SnapPoint> out = new ArrayList<>();
        out.add(nearest);
        for (SnapPoint p : pool) {
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
     * The network nodes onward from {@code from}'s edge (both its ends, then breadth first along the edges), each as a
     * point at the end of an edge leading to it, while the nodes stay within {@code maxDistance} of {@code (x, floorY, z)}.
     * Deterministic: edges in index order. The query distance of each point is its distance to the destination.
     */
    static List<SnapPoint> roadOnward(RoadNetworkSnapshot snapshot, SnapPoint from, double x, double floorY, double z,
                                      double maxDistance) {
        List<SnapPoint> out = new ArrayList<>();
        var start = snapshot.edge(from.edgeId());
        if (start.isEmpty()) {
            return out;
        }
        ArrayDeque<int[]> queue = new ArrayDeque<>(); // {node id, edge id it was reached by}
        Set<Integer> seen = new HashSet<>();
        queue.add(new int[] {start.get().fromNodeId(), start.get().id()});
        queue.add(new int[] {start.get().toNodeId(), start.get().id()});
        while (!queue.isEmpty()) {
            int[] next = queue.poll();
            int node = next[0];
            if (!seen.add(node)) {
                continue;
            }
            SnapPoint p = atEndOf(snapshot, next[1], node, x, floorY, z);
            if (p.distance() > maxDistance) {
                continue; // out of reach: not a candidate, and the road beyond it is not followed
            }
            out.add(p);
            int[] incident = snapshot.incidentEdgeIndexes(node);
            java.util.Arrays.sort(incident);
            for (int i : incident) {
                var e = snapshot.edgeAt(i);
                int other = e.fromNodeId() == node ? e.toNodeId() : e.fromNodeId();
                if (!seen.contains(other)) {
                    queue.add(new int[] {other, e.id()});
                }
            }
        }
        return out;
    }

    private static SnapPoint atEndOf(RoadNetworkSnapshot snapshot, int edgeId, int nodeId, double x, double floorY,
                                     double z) {
        var edge = snapshot.requireEdge(edgeId);
        double along = edge.fromNodeId() == nodeId ? 0 : snapshot.polyline(edge).length();
        SnapPoint p = SnapPoint.onEdge(snapshot, edgeId, along);
        return new SnapPoint(p.edgeId(), p.segmentIndex(), p.t(), p.point(), distance(p, x, floorY, z), p.along());
    }

    private static double distance(SnapPoint p, double x, double floorY, double z) {
        return EdgePolyline.distance(p.point(), new double[] {x, floorY, z});
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

        /** F3: road plus {@code walkWeight} times walk: the score {@link #best(List, double)} compares. */
        public double score(double walkWeight) {
            return roadLength + walkWeight * walkLength;
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

    /**
     * F3 (live smoke test of 2026-11-19): the option with the least {@link Option#score road plus {@code walkWeight} times
     * walk}. With a weight well above 1 a block walked counts more than a block of road, so the road carries the route
     * as far as it usefully goes and the walk starts as late as it can: a road point near the destination in a straight
     * line no longer wins with a longer walk. A tie (within a hundredth of a block) goes to the earlier one.
     * {@code walkWeight} 1 is {@link #best(List)}.
     */
    public static Optional<Option> best(List<Option> options, double walkWeight) {
        if (!(walkWeight >= 1)) {
            throw new IllegalArgumentException("walkWeight must be at least 1 (got: " + walkWeight + ")");
        }
        Option best = null;
        for (Option o : options) {
            if (best == null || o.score(walkWeight) < best.score(walkWeight) - 0.01) {
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

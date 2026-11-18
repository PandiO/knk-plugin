package net.knightsandkings.knk.core.roads.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;

/**
 * A walk link (off-road navigation DESIGN § 5.2, decisions O12, O17): a walk path between two road nodes that the road
 * A* may take instead of the road between them, found by a bounded walk search over loaded terrain and cached. Its own
 * edge kind with its own id ({@link WalkLinkId}); it is never put into a {@link RoadNetworkSnapshot} and never becomes
 * a {@link RoadEdge}.
 *
 * <p>The path is in the road network's convention: floor block positions ({@code {x, floorY, z}}, block corner, as
 * {@link RoadEdge#geometry()} points; a walk path's block centres minus 0.5 in x and z), from the From node's floor to
 * the To node's. The tags are what a road edge carries for the per-player {@link AccessPolicy}: the WorldGuard regions
 * and gate doors the path passes, in path order. Immutable; safe on any thread.
 *
 * @param id          the link's id (its From and To node)
 * @param world       the world the link lies in
 * @param path        floor block positions, From node first, at least two points
 * @param length      blocks walked (the walk path's length)
 * @param walkCost    the walk search's cost of the path (length plus its penalties)
 * @param regionIds   WorldGuard regions the path passes, in order
 * @param gateDoorIds gate doors whose cells the path passes, in order
 * @param chunks      the chunks ({@link #chunkKey}) of the path's box widened by one block: a block change in one of
 *                    them drops the link (O18)
 */
public record WalkLink(WalkLinkId id, String world, List<double[]> path, double length, double walkCost,
                       List<String> regionIds, List<Integer> gateDoorIds, Set<Long> chunks) {

    /**
     * The id of the probe edge {@link #tagsEdge()} hands a policy: below every edge id (stored and pieces are
     * non-negative), never in a snapshot, and never cached by {@link CompositeAccessPolicy#checkWalkLink}.
     */
    public static final int PROBE_EDGE_ID = Integer.MIN_VALUE;

    public WalkLink {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(path, "path");
        if (path.size() < 2) {
            throw new IllegalArgumentException(id + ": a link path needs at least two points");
        }
        List<double[]> copy = new ArrayList<>(path.size());
        for (double[] p : path) {
            if (p == null || p.length != 3) {
                throw new IllegalArgumentException(id + ": path points must be {x, y, z}");
            }
            copy.add(p.clone());
        }
        path = Collections.unmodifiableList(copy);
        if (!(length > 0) || !(walkCost >= length - 1e-9)) {
            throw new IllegalArgumentException(id + ": length must be positive and the cost at least the length");
        }
        regionIds = List.copyOf(regionIds);
        gateDoorIds = List.copyOf(gateDoorIds);
        chunks = Set.copyOf(chunks);
    }

    public int fromNode() {
        return id.fromNode();
    }

    public int toNode() {
        return id.toNode();
    }

    /**
     * A link from a walk path: {@code walkPoints} are the path's floor points as the walk search gives them (block
     * centres, {@code WalkPath.points()}), turned into the network's convention (minus 0.5 in x and z) and pinned to the
     * two nodes' floor positions at the ends, so the route's polyline runs on from the road without a gap.
     */
    public static WalkLink ofWalkPath(WalkLinkId id, String world, double[] fromNode, double[] toNode,
                                      List<double[]> walkPoints, double length, double walkCost, List<String> regionIds,
                                      List<Integer> gateDoorIds) {
        List<double[]> path = new ArrayList<>(walkPoints.size() + 2);
        path.add(fromNode.clone());
        for (double[] p : walkPoints) {
            double[] q = {p[0] - 0.5, p[1], p[2] - 0.5};
            if (!same(path.get(path.size() - 1), q)) {
                path.add(q);
            }
        }
        if (!same(path.get(path.size() - 1), toNode)) {
            path.add(toNode.clone());
        }
        if (path.size() < 2) {
            path.add(toNode.clone());
        }
        double straight = EdgePolyline.distance(fromNode, toNode);
        double walked = Math.max(length, straight);
        return new WalkLink(id, world, path, Math.max(walked, 1e-6), Math.max(walkCost, walked), regionIds, gateDoorIds,
            chunksAround(path, 1));
    }

    private static boolean same(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9 && Math.abs(a[2] - b[2]) < 1e-9;
    }

    /** The packed key of chunk ({@code chunkX}, {@code chunkZ}), as {@link #chunks} holds them. */
    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    /** The chunks of the box around {@code path} (floor block positions) widened by {@code margin} blocks. */
    public static Set<Long> chunksAround(List<double[]> path, int margin) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (double[] p : path) {
            int x = (int) Math.floor(p[0] + 0.5);
            int z = (int) Math.floor(p[2] + 0.5);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        Set<Long> out = new java.util.HashSet<>();
        for (int cx = Math.floorDiv(minX - margin, 16); cx <= Math.floorDiv(maxX + margin, 16); cx++) {
            for (int cz = Math.floorDiv(minZ - margin, 16); cz <= Math.floorDiv(maxZ + margin, 16); cz++) {
                out.add(chunkKey(cx, cz));
            }
        }
        return out;
    }

    /**
     * The link's tags as an edge, only for asking an {@link AccessPolicy} about them: id {@link #PROBE_EDGE_ID}, the
     * link's end points as geometry, its regions and gate doors, no profile, street, flags or domains. Never routed on.
     */
    public RoadEdge tagsEdge() {
        double[] a = path.get(0);
        double[] b = path.get(path.size() - 1);
        List<int[]> geometry = List.of(
            new int[] {(int) Math.round(a[0]), (int) Math.round(a[1]), (int) Math.round(a[2])},
            new int[] {(int) Math.round(b[0]), (int) Math.round(b[1]), (int) Math.round(b[2])});
        return new RoadEdge(PROBE_EDGE_ID, fromNode(), toNode(), geometry, length, 1, OptionalInt.empty(),
            OptionalInt.empty(), 1.0, EnumSet.noneOf(RoadEdgeFlag.class), gateDoorIds, List.of(), regionIds,
            RoadEdgeSource.DETECTED, false);
    }

    /** Polyline length of {@link #path} (≤ {@link #length}, as a road edge's polyline). */
    public double polylineLength() {
        double sum = 0;
        for (int i = 1; i < path.size(); i++) {
            sum += EdgePolyline.distance(path.get(i - 1), path.get(i));
        }
        return sum;
    }

    @Override
    public String toString() {
        return "WalkLink[" + id + ", " + world + ", length=" + String.format(java.util.Locale.ROOT, "%.1f", length) + "]";
    }
}

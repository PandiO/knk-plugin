package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.build.MaskBuilder.Region;
import net.knightsandkings.knk.core.roads.build.MaskBuilder.Seed;
import net.knightsandkings.knk.core.roads.build.NodeMatcher.PreviousGraph;
import net.knightsandkings.knk.core.roads.build.NodeMatcher.PreviousNode;
import net.knightsandkings.knk.core.roads.build.SkeletonGraph.Anchor;
import net.knightsandkings.knk.core.roads.build.SkeletonGraph.Chain;
import net.knightsandkings.knk.core.roads.build.TileBuildResult.Edge;
import net.knightsandkings.knk.core.roads.build.TileBuildResult.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Builds one road tile (DESIGN §5.4-§5.7): seeds → mask → distance transform → thinning → graph →
 * edges with geometry, length, width, profile and gate doors → stable ids against the previous build.
 * Pure: no I/O, no threads; the world is read through the {@link SurfaceGrid} the caller captured
 * (Phase 3 wraps chunk snapshots), gates through {@link GateCells}, towns through the
 * {@link ProfileSet}'s {@link ScopeLookup}. Domain/region tagging is not here (needs WorldGuard).
 *
 * <p>Coordinates: every position is a span's floor block (a player standing there has their feet at
 * {@code y + 1}); nodes and geometry use the same convention.
 */
public final class TileBuilder {

    /** Bump when the builder's output changes in a way that should force rebuilds ({@code RoadTile.BuilderVersion}). */
    public static final int BUILDER_VERSION = 1;

    /**
     * One build request.
     *
     * @param world         world name (carried through for the caller; the builder does not use it)
     * @param tileX         tile coordinate ({@code floor(x / tileSize)})
     * @param tileZ         tile coordinate
     * @param parameters    build tunables
     * @param seeds         Domain Locations, survey/admin seeds and neighbour boundary nodes in or
     *                      near the tile
     * @param profiles      the enabled profiles (with their town scope lookup)
     * @param gateCells     closed footprints of every gate door (plan D9)
     * @param anchors       this tile's admin Anchor nodes
     * @param previousGraph the tile's previous build for stable ids ({@link PreviousGraph#EMPTY} first time)
     */
    public record TileRequest(String world, int tileX, int tileZ, BuildParameters parameters, List<Seed> seeds,
                              ProfileSet profiles, GateCells gateCells, List<Anchor> anchors,
                              PreviousGraph previousGraph) {
        public TileRequest {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(parameters, "parameters");
            seeds = List.copyOf(Objects.requireNonNull(seeds, "seeds"));
            Objects.requireNonNull(profiles, "profiles");
            Objects.requireNonNull(gateCells, "gateCells");
            anchors = List.copyOf(Objects.requireNonNull(anchors, "anchors"));
            Objects.requireNonNull(previousGraph, "previousGraph");
        }

        /** The tile's own x/z rectangle. */
        public Region tile() {
            return Region.tile(tileX, tileZ, parameters.tileSize());
        }

        /** The rectangle the mask may occupy: the tile plus its margin. */
        public Region region() {
            return tile().grow(parameters.tileMargin());
        }
    }

    /** Build the tile. */
    public TileBuildResult build(TileRequest request, SurfaceGrid grid) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(grid, "grid");
        BuildParameters params = request.parameters();
        SpanGrid spanGrid = new SpanGrid(grid, request.profiles(), request.gateCells());

        MaskBuilder.Result masked = new MaskBuilder(spanGrid, params).build(request.seeds(), request.region());
        RoadMask mask = masked.mask();
        List<BuildWarning> warnings = new ArrayList<>(masked.warnings());

        int[] dt = DistanceTransform.compute(mask);
        boolean[] skeleton = Thinning.thin(mask);
        SkeletonGraph.Result graph = new SkeletonGraph(mask, skeleton, dt, params, request.profiles(), request.tile())
            .extract(request.anchors());
        warnings.addAll(graph.warnings());

        // Stable ids and positions.
        NodeMatcher matcher = new NodeMatcher(params);
        List<NodeMatcher.Candidate> candidates = new ArrayList<>();
        for (SkeletonGraph.Node node : graph.nodes()) {
            candidates.add(new NodeMatcher.Candidate(node.x(), node.y(), node.z(), node.anchorId(), node.kind()));
        }
        int[] existing = matcher.matchNodes(candidates, request.previousGraph());
        int[][] positions = new int[graph.nodes().size()][];
        boolean[] locked = new boolean[graph.nodes().size()];
        for (SkeletonGraph.Node node : graph.nodes()) {
            int i = node.id();
            int[] pos = {node.x(), node.y(), node.z()};
            if (existing[i] != NodeMatcher.UNMATCHED) {
                PreviousNode previous = request.previousGraph().node(existing[i]);
                boolean keepsBorder = node.kind() != RoadNodeKind.BOUNDARY || onBorder(request.tile(), previous);
                if (previous != null && previous.locked() && request.tile().contains(previous.x(), previous.z()) && keepsBorder) {
                    pos = new int[] {previous.x(), previous.y(), previous.z()};
                    locked[i] = true;
                }
            }
            positions[i] = pos;
        }
        int[] into = mergeIntoLockedNodes(graph, existing, locked, positions, mask, params.lockedNodeReach());
        List<Node> nodes = new ArrayList<>();
        for (SkeletonGraph.Node node : graph.nodes()) {
            int i = node.id();
            if (into[i] != i) {
                continue;
            }
            int[] pos = positions[i];
            nodes.add(new Node(key(i), existing[i] == NodeMatcher.UNMATCHED ? OptionalInt.empty() : OptionalInt.of(existing[i]),
                pos[0], pos[1], pos[2], node.kind()));
        }

        ProfileMatcher profileMatcher = new ProfileMatcher(request.profiles());
        Map<Long, Edge> edgesByPair = new LinkedHashMap<>();
        for (Chain chain : graph.chains()) {
            int from = into[chain.from()];
            int to = into[chain.to()];
            if (from == to) {
                continue; // the chain between a duplicate and the locked node it merged into
            }
            List<int[]> polyline = polyline(mask, chain, positions[from], positions[to]);
            double length = Rdp.length(polyline);
            long pair = ((long) Math.min(from, to) << 32) | Math.max(from, to);
            Edge kept = edgesByPair.get(pair);
            if (kept != null && kept.length() <= length) {
                continue; // a merge left two chains between the same nodes: keep the shorter (API unique pair)
            }
            List<int[]> geometry = Rdp.simplify(polyline, params.rdpEpsilon());
            double avgWidth = averageWidth(dt, chain.spans());
            OptionalInt profileId = profileMatcher.match(mask, dt, chain.spans());
            List<Integer> doors = gateDoors(mask, chain.spans());
            OptionalInt edgeId = matcher.matchEdge(existing[from], existing[to], geometry, request.previousGraph());
            edgesByPair.put(pair, new Edge(edgeId, key(from), key(to), geometry, length, avgWidth, profileId,
                doors, List.of(), List.of()));
        }
        return new TileBuildResult(BUILDER_VERSION, mask.size(), mask.levelCount(), nodes,
            new ArrayList<>(edgesByPair.values()), warnings);
    }

    /**
     * Locked nodes as exclusion zones (smoke test fix plan 5.5 item 6): an admin merged two junctions
     * into one, or locked the one junction a fragmented plaza should be, and the rebuild must not
     * bring the duplicates back. A node that matched no previous node, is a Junction or Endpoint (a
     * Boundary stays for stitching, an Anchor is the admin's own) and is joined by a chain no longer
     * than {@code reach} to a node standing on a locked node - with its own position within
     * {@code reach} of that locked position - merges into it; its other chains then start at the
     * locked node. Repeats until nothing changes.
     *
     * @return per node, the node it is emitted as (itself, or the locked node it merged into)
     */
    static int[] mergeIntoLockedNodes(SkeletonGraph.Result graph, int[] existing, boolean[] locked, int[][] positions,
                                      RoadMask mask, double reach) {
        int count = graph.nodes().size();
        int[] into = new int[count];
        for (int i = 0; i < count; i++) {
            into[i] = i;
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Chain chain : graph.chains()) {
                int a = into[chain.from()];
                int b = into[chain.to()];
                if (a == b || chainLength(mask, chain) > reach) {
                    continue;
                }
                int keep = locked[a] && absorbable(graph, existing, b) ? a : locked[b] && absorbable(graph, existing, a) ? b : -1;
                if (keep < 0) {
                    continue;
                }
                int gone = keep == a ? b : a;
                if (distance(positions[keep], positions[gone]) > reach) {
                    continue;
                }
                for (int i = 0; i < count; i++) {
                    if (into[i] == gone) {
                        into[i] = keep;
                    }
                }
                changed = true;
            }
        }
        return into;
    }

    private static boolean absorbable(SkeletonGraph.Result graph, int[] existing, int node) {
        RoadNodeKind kind = graph.nodes().get(node).kind();
        return existing[node] == NodeMatcher.UNMATCHED && (kind == RoadNodeKind.JUNCTION || kind == RoadNodeKind.ENDPOINT);
    }

    private static double chainLength(RoadMask mask, Chain chain) {
        double length = 0;
        int[] spans = chain.spans();
        for (int k = 1; k < spans.length; k++) {
            length += mask.distance(spans[k - 1], spans[k]);
        }
        return length;
    }

    private static double distance(int[] a, int[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean onBorder(Region tile, PreviousNode node) {
        return node != null && (node.x() == tile.minX() || node.x() == tile.maxX() || node.z() == tile.minZ() || node.z() == tile.maxZ());
    }

    static String key(int nodeIndex) {
        return "n" + nodeIndex;
    }

    /** The chain's span positions, closed onto the two node positions (which may be off the chain). */
    static List<int[]> polyline(RoadMask mask, Chain chain, int[] from, int[] to) {
        List<int[]> points = new ArrayList<>(chain.spans().length + 2);
        points.add(from);
        for (int span : chain.spans()) {
            int[] p = {mask.x(span), mask.y(span), mask.z(span)};
            if (!samePoint(points.get(points.size() - 1), p)) {
                points.add(p);
            }
        }
        if (!samePoint(points.get(points.size() - 1), to)) {
            points.add(to);
        }
        if (points.size() == 1) {
            points.add(to.clone());
        }
        return points;
    }

    private static boolean samePoint(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    static double averageWidth(int[] dt, int[] spans) {
        if (spans.length == 0) {
            return 0;
        }
        double sum = 0;
        for (int span : spans) {
            sum += DistanceTransform.width(dt[span]);
        }
        return sum / spans.length;
    }

    static List<Integer> gateDoors(RoadMask mask, int[] spans) {
        Set<Integer> doors = new LinkedHashSet<>();
        for (int span : spans) {
            int door = mask.gateDoor(span);
            if (door != RoadMask.NONE) {
                doors.add(door);
            }
        }
        return new ArrayList<>(doors);
    }
}

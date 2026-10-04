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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.PriorityQueue;
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

    /** A closing walk ({@link #closingPath}) may be this many times the straight distance between chain end and node … */
    static final double CLOSING_STRETCH = 2.0;
    /** … plus this many blocks (a short gap around a planter or lamp post). */
    static final double CLOSING_SLACK = 8.0;

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
        List<SkeletonGraph.Pruned> pruned = new ArrayList<>();
        List<SkeletonGraph.Pruned> prunedEdges = new ArrayList<>();
        for (PreviousNode node : request.previousGraph().nodes()) {
            if (node.kind() == RoadNodeKind.PRUNED) {
                pruned.add(new SkeletonGraph.Pruned(node.id(), node.x(), node.y(), node.z()));
            } else if (node.kind() == RoadNodeKind.PRUNED_EDGE) {
                prunedEdges.add(new SkeletonGraph.Pruned(node.id(), node.x(), node.y(), node.z()));
            }
        }
        SkeletonGraph.Result graph = new SkeletonGraph(mask, skeleton, dt, params, request.profiles(), request.tile())
            .extract(request.anchors(), pruned, prunedEdges);
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

        Map<Long, Run> runsByPair = new LinkedHashMap<>();
        for (Chain chain : graph.chains()) {
            int from = into[chain.from()];
            int to = into[chain.to()];
            if (from == to) {
                continue; // the chain between a duplicate and the locked node it merged into
            }
            List<int[]> polyline = polyline(mask, chain, positions[from], positions[to]);
            Run run = new Run(from, to, polyline, chain.spans());
            Run kept = runsByPair.get(pairKey(from, to));
            if (kept != null && kept.length() <= run.length()) {
                continue; // a merge left two chains between the same nodes: keep the shorter (API unique pair)
            }
            runsByPair.put(pairKey(from, to), run);
        }
        Set<Integer> dissolved = dissolveTwoArmJunctions(runsByPair, graph, existing, locked, request.previousGraph());

        List<Node> nodes = new ArrayList<>();
        for (SkeletonGraph.Node node : graph.nodes()) {
            int i = node.id();
            if (into[i] != i || dissolved.contains(i)) {
                continue;
            }
            int[] pos = positions[i];
            nodes.add(new Node(key(i), existing[i] == NodeMatcher.UNMATCHED ? OptionalInt.empty() : OptionalInt.of(existing[i]),
                pos[0], pos[1], pos[2], node.kind()));
        }
        ProfileMatcher profileMatcher = new ProfileMatcher(request.profiles());
        List<Edge> edges = new ArrayList<>();
        for (Run run : runsByPair.values()) {
            List<int[]> geometry = Rdp.simplify(run.polyline(), params.rdpEpsilon());
            double avgWidth = averageWidth(dt, run.spans());
            OptionalInt profileId = profileMatcher.match(mask, dt, run.spans());
            List<Integer> doors = gateDoors(mask, run.spans());
            OptionalInt edgeId = matcher.matchEdge(existing[run.from()], existing[run.to()], geometry, request.previousGraph());
            edges.add(new Edge(edgeId, key(run.from()), key(run.to()), geometry, run.length(), avgWidth, profileId,
                doors, List.of(), List.of()));
        }
        return new TileBuildResult(BUILDER_VERSION, mask.size(), mask.levelCount(), nodes, edges, warnings);
    }

    /** One edge before simplification: its node indices, the closed polyline and the mask spans, oriented from → to. */
    record Run(int from, int to, List<int[]> polyline, int[] spans) {
        double length() {
            return Rdp.length(polyline);
        }

        int other(int node) {
            return node == from ? to : from;
        }

        /** The polyline ending at {@code node}. */
        List<int[]> polylineTowards(int node) {
            if (node == to) {
                return polyline;
            }
            List<int[]> reversed = new ArrayList<>(polyline);
            java.util.Collections.reverse(reversed);
            return reversed;
        }

        int[] spansTowards(int node) {
            if (node == to) {
                return spans;
            }
            int[] reversed = new int[spans.length];
            for (int k = 0; k < spans.length; k++) {
                reversed[k] = spans[spans.length - 1 - k];
            }
            return reversed;
        }
    }

    static long pairKey(int a, int b) {
        return ((long) Math.min(a, b) << 32) | Math.max(a, b);
    }

    /**
     * Smoke test 2026-10-02: a junction in a straight road with only two edges (#3615). Steps after the
     * spur pruning can leave a Junction with two arms - the loop/parallel split, the tile-border cut, an
     * arm merged into a locked node - and a two-arm junction is no junction. Each such node is joined
     * away: its two runs become one. It stays when it is locked (an admin's), when joining would make a
     * loop or a second run between the same two nodes (the API's unique pair), or when its previous build
     * gave it three or more edges (a recorded edge the builder can't see may end there). Repeats until
     * nothing changes.
     *
     * @return the joined-away node indices (not emitted)
     */
    static Set<Integer> dissolveTwoArmJunctions(Map<Long, Run> runs, SkeletonGraph.Result graph, int[] existing,
                                                boolean[] locked, PreviousGraph previous) {
        Set<Integer> dissolved = new java.util.HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<Integer, List<Run>> byNode = new java.util.TreeMap<>();
            for (Run run : runs.values()) {
                byNode.computeIfAbsent(run.from(), k -> new ArrayList<>()).add(run);
                byNode.computeIfAbsent(run.to(), k -> new ArrayList<>()).add(run);
            }
            for (Map.Entry<Integer, List<Run>> entry : byNode.entrySet()) {
                int n = entry.getKey();
                List<Run> arms = entry.getValue();
                if (arms.size() != 2 || locked[n] || graph.nodes().get(n).kind() != RoadNodeKind.JUNCTION
                    || previousDegree(previous, existing[n]) >= 3) {
                    continue;
                }
                Run first = arms.get(0);
                Run second = arms.get(1);
                int a = first.other(n);
                int b = second.other(n);
                if (a == b || runs.containsKey(pairKey(a, b))) {
                    continue;
                }
                List<int[]> polyline = new ArrayList<>(first.polylineTowards(n));
                List<int[]> tail = second.polylineTowards(n);
                for (int k = tail.size() - 2; k >= 0; k--) {
                    polyline.add(tail.get(k));
                }
                int[] head = first.spansTowards(n);
                int[] rest = second.spansTowards(n);
                int[] spans = java.util.Arrays.copyOf(head, head.length + rest.length);
                for (int k = 0; k < rest.length; k++) {
                    spans[head.length + k] = rest[rest.length - 1 - k];
                }
                runs.remove(pairKey(n, a));
                runs.remove(pairKey(n, b));
                runs.put(pairKey(a, b), new Run(a, b, polyline, spans));
                dissolved.add(n);
                changed = true;
                break; // byNode is stale now
            }
        }
        return dissolved;
    }

    private static int previousDegree(PreviousGraph previous, int previousId) {
        if (previousId == NodeMatcher.UNMATCHED) {
            return 0;
        }
        int degree = 0;
        for (NodeMatcher.PreviousEdge edge : previous.edges()) {
            if (edge.fromNodeId() == previousId || edge.toNodeId() == previousId) {
                degree++;
            }
        }
        return degree;
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

    /**
     * The chain's span positions, closed onto the two node positions (which may be off the chain). A
     * plaza or cluster junction sits at its core while its chains start at the edge of its footprint,
     * up to tens of blocks away and often below it (smoke test 2026-10-04): that gap is closed along
     * the mask ({@link #closingPath}), so the edge climbs the plaza's stairs instead of cutting through
     * the ground. A node off the mask (a locked or anchor position) keeps the straight closing.
     */
    static List<int[]> polyline(RoadMask mask, Chain chain, int[] from, int[] to) {
        int[] spans = chain.spans();
        List<int[]> points = new ArrayList<>(spans.length + 2);
        points.add(from);
        if (spans.length > 0) {
            List<int[]> head = closingPath(mask, from, spans[0]);
            for (int k = head.size() - 1; k >= 0; k--) {
                addPoint(points, head.get(k));
            }
        }
        for (int span : spans) {
            addPoint(points, new int[] {mask.x(span), mask.y(span), mask.z(span)});
        }
        if (spans.length > 0) {
            for (int[] p : closingPath(mask, to, spans[spans.length - 1])) {
                addPoint(points, p);
            }
        }
        addPoint(points, to);
        if (points.size() == 1) {
            points.add(to.clone());
        }
        return points;
    }

    private static void addPoint(List<int[]> points, int[] p) {
        if (!samePoint(points.get(points.size() - 1), p)) {
            points.add(p);
        }
    }

    /**
     * The shortest walk over mask links from the chain's end span to the span under a node position,
     * as the positions strictly between them, ordered from the chain end towards the node; empty when
     * they touch, when the node is off the mask, or when no walk is found within
     * {@code CLOSING_STRETCH × the straight distance + CLOSING_SLACK} (the straight closing then
     * stays). Dijkstra with the polyline's own step lengths (1, √2, with height), visiting only spans
     * that close enough.
     */
    static List<int[]> closingPath(RoadMask mask, int[] node, int chainEnd) {
        int target = mask.indexOf(node[0], node[1], node[2]);
        if (target == RoadMask.NONE || target == chainEnd) {
            return List.of();
        }
        double straight = mask.distance(chainEnd, target);
        if (straight < 1.5) {
            return List.of();
        }
        double limit = CLOSING_STRETCH * straight + CLOSING_SLACK;
        Map<Integer, Double> cost = new HashMap<>();
        Map<Integer, Integer> parent = new HashMap<>();
        PriorityQueue<double[]> queue = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        cost.put(chainEnd, 0.0);
        queue.add(new double[] {0.0, chainEnd});
        while (!queue.isEmpty()) {
            double[] head = queue.poll();
            int i = (int) head[1];
            if (head[0] > cost.get(i)) {
                continue;
            }
            if (i == target) {
                List<int[]> path = new ArrayList<>();
                for (int k = parent.get(target); k != chainEnd; k = parent.get(k)) {
                    path.add(new int[] {mask.x(k), mask.y(k), mask.z(k)});
                }
                Collections.reverse(path);
                return path;
            }
            for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
                int nb = mask.neighbour(i, d);
                if (nb == RoadMask.NONE) {
                    continue;
                }
                double c = head[0] + mask.distance(i, nb);
                if (c + mask.distance(nb, target) > limit) {
                    continue;
                }
                Double known = cost.get(nb);
                if (known == null || c < known) {
                    cost.put(nb, c);
                    parent.put(nb, i);
                    queue.add(new double[] {c, nb});
                }
            }
        }
        return List.of();
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

package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.build.MaskBuilder.Region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * From the centreline to nodes and chains (DESIGN §5.6, plan D7):
 *
 * <ol>
 *   <li><b>Classify</b> skeleton spans by their number of skeleton neighbours: 1 = endpoint,
 *       2 = along the road, ≥ 3 = junction candidate.</li>
 *   <li><b>Plazas</b> (step 4, done first so the cluster step sees them): mask spans whose local width
 *       ({@code 2·dt − 1}) exceeds the {@code widthMax} of every applicable profile listing their
 *       floor material form plaza groups; each group with skeleton spans becomes one Junction at
 *       its widest span, and every skeleton span inside it belongs to that junction.</li>
 *   <li><b>Cluster</b> junction candidates that are within {@code junctionClusterRadius} skeleton
 *       steps of each other into one Junction at the candidate nearest the cluster's centroid; the
 *       skeleton spans on the short paths between them belong to the junction too.</li>
 *   <li><b>Anchors</b>: an admin anchor within {@code nodeMatchDistance} of a skeleton span turns
 *       that span (or the node already there) into an Anchor node; chains are split there.</li>
 *   <li><b>Chains</b>: maximal runs of non-node skeleton spans between two node spans.</li>
 *   <li><b>Endpoint extension</b>: thinning erodes a road end by about half its width, so each
 *       Endpoint is walked back out along the mask in the chain's last direction (at most {@code dt}
 *       steps) — the centreline then reaches the last row of road blocks.</li>
 *   <li><b>Prune spurs</b> (step 3): an Endpoint–Junction chain shorter than
 *       {@code max(minSpurLength, the chain's own median width)} is removed, shortest first; a junction
 *       left with two chains dissolves into one chain, one left with a single chain becomes an
 *       Endpoint. Isolated Endpoint–Endpoint chains shorter than {@code minSpurLength} go too.</li>
 *   <li><b>Loops and parallel chains</b> get a Junction inserted midway so every edge has a distinct
 *       node pair (the API's unique-pair rule).</li>
 *   <li><b>Tile border</b> (D7): chains are cut where they leave the tile; the last span inside is a
 *       Boundary node (an Endpoint there becomes Boundary; another node keeps its kind). Nodes and
 *       chain parts outside the tile are dropped — the neighbour tile builds them.</li>
 * </ol>
 *
 * Node positions are span floor positions, except Anchor nodes (the admin's position) — the chain
 * geometry is closed onto the node position by {@link TileBuilder}.
 */
public final class SkeletonGraph {

    /** An admin Anchor node of this tile (kind Anchor, Manual + Locked on the API side). */
    public record Anchor(int id, int x, int y, int z) {
    }

    /**
     * A graph node.
     *
     * @param id       0-based index into the result's node list
     * @param x        position (the span's floor block, or the anchor's stored position)
     * @param kind     node kind
     * @param anchorId the admin anchor this node stands for, if any
     * @param span     mask index of the span the node sits on ({@link RoadMask#NONE} never happens for
     *                 detected nodes; anchors keep the span they snapped to)
     */
    public record Node(int id, int x, int y, int z, RoadNodeKind kind, OptionalInt anchorId, int span) {
    }

    /** A chain of skeleton spans from node {@code from} to node {@code to}, node spans included. */
    public record Chain(int from, int to, int[] spans) {
    }

    public record Result(List<Node> nodes, List<Chain> chains, List<BuildWarning> warnings) {
    }

    public static final String WARN_ANCHOR_OFF_ROAD = "Anchor is not within reach of the road centreline";
    public static final String WARN_ANCHOR_DUPLICATE = "Second anchor on the same centreline span ignored";

    private static final int MAX_SPLIT_ROUNDS = 8;

    private final RoadMask mask;
    private final boolean[] skeleton;
    private final int[] dt;
    private final BuildParameters params;
    private final ProfileSet profiles;
    private final Region tile;

    private int[] nodeOf;
    private final List<WorkNode> nodes = new ArrayList<>();
    private final List<WorkChain> chains = new ArrayList<>();
    private final List<BuildWarning> warnings = new ArrayList<>();

    private static final class WorkNode {
        int span;
        int x;
        int y;
        int z;
        RoadNodeKind kind;
        int anchorId = -1;
        boolean alive = true;
        final List<Integer> chainIds = new ArrayList<>();

        int aliveChains(List<WorkChain> chains) {
            int count = 0;
            for (int id : chainIds) {
                if (chains.get(id).alive) {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class WorkChain {
        int from;
        int to;
        int[] spans;
        boolean alive = true;
    }

    public SkeletonGraph(RoadMask mask, boolean[] skeleton, int[] dt, BuildParameters params,
                         ProfileSet profiles, Region tile) {
        this.mask = Objects.requireNonNull(mask, "mask");
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        this.dt = Objects.requireNonNull(dt, "dt");
        this.params = Objects.requireNonNull(params, "params");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.tile = Objects.requireNonNull(tile, "tile");
        if (skeleton.length != mask.size() || dt.length != mask.size()) {
            throw new IllegalArgumentException("skeleton/dt must have one entry per mask span");
        }
    }

    /** Extract nodes and chains; call once. */
    public Result extract(List<Anchor> anchors) {
        Objects.requireNonNull(anchors, "anchors");
        int n = mask.size();
        nodeOf = new int[n];
        Arrays.fill(nodeOf, -1);
        int[] degree = new int[n];
        for (int i = 0; i < n; i++) {
            degree[i] = skeleton[i] ? Thinning.degree(mask, skeleton, i) : 0;
        }

        collapsePlazas(degree);
        clusterJunctions(degree);
        for (int i = 0; i < n; i++) {
            if (skeleton[i] && degree[i] == 1 && nodeOf[i] < 0) {
                newNode(i, RoadNodeKind.ENDPOINT);
            }
        }
        seedNodelessComponents();
        placeAnchors(anchors);
        traceChains(degree);
        extendEndpoints();
        pruneSpurs();
        splitLoopsAndParallels();
        cutAtTileBorder();
        return emit();
    }

    // ---- nodes -------------------------------------------------------------------------------

    private int newNode(int span, RoadNodeKind kind) {
        WorkNode node = new WorkNode();
        node.span = span;
        node.x = mask.x(span);
        node.y = mask.y(span);
        node.z = mask.z(span);
        node.kind = kind;
        nodes.add(node);
        int id = nodes.size() - 1;
        nodeOf[span] = id;
        return id;
    }

    private double chainLength(WorkChain chain) {
        double length = 0;
        for (int k = 1; k < chain.spans.length; k++) {
            length += mask.distance(chain.spans[k - 1], chain.spans[k]);
        }
        return length;
    }

    // ---- 2. plazas ---------------------------------------------------------------------------

    private void collapsePlazas(int[] degree) {
        int n = mask.size();
        boolean[] plaza = new boolean[n];
        for (int i = 0; i < n; i++) {
            OptionalInt widthMax = profiles.maxWidthMax(mask.floor(i), mask.x(i), mask.z(i));
            plaza[i] = widthMax.isPresent() && DistanceTransform.width(dt[i]) > widthMax.getAsInt();
        }
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        for (int start = 0; start < n; start++) {
            if (!plaza[start] || seen[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            List<Integer> group = new ArrayList<>();
            while (head < tail) {
                int i = queue[head++];
                group.add(i);
                for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
                    int nb = mask.neighbour(i, d);
                    if (nb != RoadMask.NONE && plaza[nb] && !seen[nb]) {
                        seen[nb] = true;
                        queue[tail++] = nb;
                    }
                }
            }
            List<Integer> skeletonSpans = new ArrayList<>();
            for (int i : group) {
                if (skeleton[i]) {
                    skeletonSpans.add(i);
                }
            }
            if (skeletonSpans.isEmpty()) {
                continue;
            }
            double cx = 0;
            double cy = 0;
            double cz = 0;
            for (int i : group) {
                cx += mask.x(i);
                cy += mask.y(i);
                cz += mask.z(i);
            }
            cx /= group.size();
            cy /= group.size();
            cz /= group.size();
            int centre = -1;
            for (int i : group) {
                if (centre < 0 || dt[i] > dt[centre]
                    || (dt[i] == dt[centre] && distanceTo(i, cx, cy, cz) < distanceTo(centre, cx, cy, cz))) {
                    centre = i;
                }
            }
            // The junction sits on the widest span even if the skeleton does not pass through it.
            int node = newNode(centre, RoadNodeKind.JUNCTION);
            for (int i : skeletonSpans) {
                nodeOf[i] = node;
            }
            // Skeleton spans strictly inside the group are no junction candidates any more.
            for (int i : skeletonSpans) {
                degree[i] = Math.min(degree[i], 2);
            }
        }
    }

    private double distanceTo(int i, double x, double y, double z) {
        double dx = mask.x(i) - x;
        double dy = mask.y(i) - y;
        double dz = mask.z(i) - z;
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- 3. junction clusters ----------------------------------------------------------------

    private void clusterJunctions(int[] degree) {
        int n = mask.size();
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (skeleton[i] && degree[i] >= 3 && nodeOf[i] < 0) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        int[] parent = new int[n];
        Arrays.fill(parent, -1);
        for (int c : candidates) {
            parent[c] = c;
        }
        // Spans on a short skeleton path between two candidates, keyed by one of the candidates.
        List<int[]> absorbed = new ArrayList<>();
        int radius = params.junctionClusterRadius();
        int[] dist = new int[n];
        int[] via = new int[n];
        for (int c : candidates) {
            Arrays.fill(dist, -1);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            dist[c] = 0;
            via[c] = -1;
            queue.add(c);
            while (!queue.isEmpty()) {
                int i = queue.poll();
                if (dist[i] >= radius) {
                    continue;
                }
                for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
                    int nb = mask.neighbour(i, d);
                    if (nb == RoadMask.NONE || !skeleton[nb] || dist[nb] != -1) {
                        continue;
                    }
                    if (nodeOf[nb] >= 0) {
                        continue; // a plaza junction or an endpoint: never walked through
                    }
                    dist[nb] = dist[i] + 1;
                    via[nb] = i;
                    if (parent[nb] != -1) {
                        union(parent, c, nb);
                        for (int s = via[nb]; s != c && s != -1; s = via[s]) {
                            absorbed.add(new int[] {s, c});
                        }
                    } else {
                        queue.add(nb);
                    }
                }
            }
        }
        Map<Integer, List<Integer>> clusters = new HashMap<>();
        for (int c : candidates) {
            clusters.computeIfAbsent(find(parent, c), k -> new ArrayList<>()).add(c);
        }
        Map<Integer, Integer> nodeOfRoot = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : clusters.entrySet()) {
            List<Integer> members = e.getValue();
            double cx = 0;
            double cy = 0;
            double cz = 0;
            for (int i : members) {
                cx += mask.x(i);
                cy += mask.y(i);
                cz += mask.z(i);
            }
            cx /= members.size();
            cy /= members.size();
            cz /= members.size();
            int centre = -1;
            for (int i : members) {
                if (centre < 0 || distanceTo(i, cx, cy, cz) < distanceTo(centre, cx, cy, cz)
                    || (distanceTo(i, cx, cy, cz) == distanceTo(centre, cx, cy, cz) && i < centre)) {
                    centre = i;
                }
            }
            int node = newNode(centre, RoadNodeKind.JUNCTION);
            nodeOfRoot.put(e.getKey(), node);
            for (int i : members) {
                nodeOf[i] = node;
            }
        }
        for (int[] pair : absorbed) {
            int span = pair[0];
            if (nodeOf[span] < 0) {
                nodeOf[span] = nodeOfRoot.get(find(parent, pair[1]));
            }
        }
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }

    // ---- 3b. closed loops --------------------------------------------------------------------

    /**
     * A skeleton component without any node span (a ring road: every span has two neighbours) would
     * never be traced. It gets one Junction at its lowest-index span; the loop chain from that node
     * to itself is then split into three edges like any other loop.
     */
    private void seedNodelessComponents() {
        int n = mask.size();
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        for (int start = 0; start < n; start++) {
            if (!skeleton[start] || seen[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            boolean hasNode = false;
            while (head < tail) {
                int i = queue[head++];
                hasNode |= nodeOf[i] >= 0;
                for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
                    int nb = mask.neighbour(i, d);
                    if (nb != RoadMask.NONE && skeleton[nb] && !seen[nb]) {
                        seen[nb] = true;
                        queue[tail++] = nb;
                    }
                }
            }
            if (!hasNode && tail > 1) {
                newNode(start, RoadNodeKind.JUNCTION);
            }
        }
    }

    // ---- 4. anchors --------------------------------------------------------------------------

    private void placeAnchors(List<Anchor> anchors) {
        double maxDistance = params.nodeMatchDistance();
        for (Anchor anchor : anchors) {
            int best = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int i = 0; i < mask.size(); i++) {
                if (!skeleton[i]) {
                    continue;
                }
                double d = Math.sqrt(distanceTo(i, anchor.x(), anchor.y(), anchor.z()));
                if (d > maxDistance) {
                    continue;
                }
                // Nearest wins; on a tie a span that already is a node (the junction the admin
                // stood next to) beats a plain centreline span.
                boolean closer = d < bestDistance - 1e-9;
                boolean tie = Math.abs(d - bestDistance) <= 1e-9;
                if (closer || (tie && nodeOf[i] >= 0 && nodeOf[best] < 0)) {
                    bestDistance = d;
                    best = i;
                }
            }
            if (best < 0) {
                warnings.add(new BuildWarning(WARN_ANCHOR_OFF_ROAD + " (anchor " + anchor.id() + ")",
                    anchor.x(), anchor.y(), anchor.z()));
                continue;
            }
            WorkNode node;
            if (nodeOf[best] >= 0) {
                node = nodes.get(nodeOf[best]);
                if (node.anchorId >= 0) {
                    warnings.add(new BuildWarning(WARN_ANCHOR_DUPLICATE + " (anchor " + anchor.id() + ")",
                        anchor.x(), anchor.y(), anchor.z()));
                    continue;
                }
            } else {
                node = nodes.get(newNode(best, RoadNodeKind.ANCHOR));
            }
            node.kind = RoadNodeKind.ANCHOR;
            node.anchorId = anchor.id();
            node.x = anchor.x();
            node.y = anchor.y();
            node.z = anchor.z();
        }
    }

    // ---- 5. chains ---------------------------------------------------------------------------

    private void traceChains(int[] degree) {
        int n = mask.size();
        boolean[] interiorVisited = new boolean[n];
        Set<Long> directPairs = new HashSet<>();
        for (int s = 0; s < n; s++) {
            if (nodeOf[s] < 0) {
                continue;
            }
            for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
                int t = mask.neighbour(s, d);
                if (t == RoadMask.NONE || !skeleton[t]) {
                    continue;
                }
                if (nodeOf[t] >= 0) {
                    if (nodeOf[t] == nodeOf[s]) {
                        continue; // inside one junction cluster
                    }
                    long pair = pairKey(s, t);
                    if (directPairs.add(pair)) {
                        addChain(nodeOf[s], nodeOf[t], new int[] {s, t});
                    }
                    continue;
                }
                if (interiorVisited[t]) {
                    continue;
                }
                List<Integer> path = new ArrayList<>();
                path.add(s);
                int prev = s;
                int cur = t;
                while (nodeOf[cur] < 0) {
                    interiorVisited[cur] = true;
                    path.add(cur);
                    int next = RoadMask.NONE;
                    for (int dd = 0; dd < SpanGrid.DIRECTIONS; dd++) {
                        int nb = mask.neighbour(cur, dd);
                        if (nb != RoadMask.NONE && nb != prev && skeleton[nb]) {
                            next = nb;
                            break;
                        }
                    }
                    if (next == RoadMask.NONE) {
                        // Dangling (degree 0 or a lone span): make it an endpoint.
                        newNode(cur, RoadNodeKind.ENDPOINT);
                        break;
                    }
                    prev = cur;
                    cur = next;
                }
                if (nodeOf[cur] >= 0 && path.get(path.size() - 1) != cur) {
                    path.add(cur);
                }
                int[] spans = path.stream().mapToInt(Integer::intValue).toArray();
                addChain(nodeOf[s], nodeOf[cur], spans);
            }
        }
    }

    private static long pairKey(int a, int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xFFFFFFFFL);
    }

    private int addChain(int from, int to, int[] spans) {
        WorkChain chain = new WorkChain();
        chain.from = from;
        chain.to = to;
        chain.spans = spans;
        chains.add(chain);
        int id = chains.size() - 1;
        nodes.get(from).chainIds.add(id);
        if (to != from) {
            nodes.get(to).chainIds.add(id);
        }
        return id;
    }

    // ---- 5b. endpoint extension --------------------------------------------------------------

    /**
     * Thinning erodes a road's end by about half its width (a 5-wide road's centreline stops 2-3
     * spans short of the last row of blocks). Every Endpoint with one chain is therefore walked back
     * out: from the tip, keep stepping in the chain's last direction while the mask has a span there
     * that is not on the skeleton, at most {@code dt(tip)} steps (the erosion depth). The added spans
     * join the chain and the skeleton, and the node moves to the new tip. A 1-wide path's end has no
     * such span and stays put.
     */
    private void extendEndpoints() {
        for (int nodeId = 0; nodeId < nodes.size(); nodeId++) {
            WorkNode node = nodes.get(nodeId);
            if (!node.alive || node.kind != RoadNodeKind.ENDPOINT || node.chainIds.size() != 1) {
                continue;
            }
            WorkChain chain = chains.get(node.chainIds.get(0));
            if (!chain.alive || chain.spans.length < 2) {
                continue;
            }
            int[] oriented = orientedTowards(chain, nodeId); // ... → tip
            int tip = oriented[oriented.length - 1];
            int beforeTip = oriented[oriented.length - 2];
            int dir = directionOf(beforeTip, tip);
            if (dir < 0) {
                continue;
            }
            int limit = dt[tip];
            List<Integer> added = new ArrayList<>();
            int cur = tip;
            for (int step = 0; step < limit; step++) {
                int next = mask.neighbour(cur, dir);
                if (next == RoadMask.NONE || skeleton[next] || nodeOf[next] >= 0) {
                    break;
                }
                added.add(next);
                cur = next;
            }
            if (added.isEmpty()) {
                continue;
            }
            int[] extended = Arrays.copyOf(oriented, oriented.length + added.size());
            for (int k = 0; k < added.size(); k++) {
                int span = added.get(k);
                extended[oriented.length + k] = span;
                skeleton[span] = true;
            }
            chain.spans = chain.to == nodeId ? extended : reversed(extended);
            nodeOf[node.span] = -1;
            node.span = cur;
            node.x = mask.x(cur);
            node.y = mask.y(cur);
            node.z = mask.z(cur);
            nodeOf[cur] = nodeId;
        }
    }

    private int directionOf(int from, int to) {
        for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
            if (mask.neighbour(from, d) == to) {
                return d;
            }
        }
        return -1;
    }

    // ---- 6. spurs ----------------------------------------------------------------------------

    private void pruneSpurs() {
        while (true) {
            int spur = -1;
            double spurLength = Double.MAX_VALUE;
            for (int id = 0; id < chains.size(); id++) {
                WorkChain chain = chains.get(id);
                if (!chain.alive) {
                    continue;
                }
                double threshold = spurThreshold(chain);
                if (threshold < 0) {
                    continue;
                }
                double length = chainLength(chain);
                if (length < threshold && length < spurLength) {
                    spurLength = length;
                    spur = id;
                }
            }
            if (spur < 0) {
                return;
            }
            removeChain(spur);
        }
    }

    /** The length below which a chain is a spur, or -1 when the chain can never be one. */
    private double spurThreshold(WorkChain chain) {
        WorkNode a = nodes.get(chain.from);
        WorkNode b = nodes.get(chain.to);
        if (chain.from == chain.to) {
            return -1;
        }
        if (a.kind == RoadNodeKind.ENDPOINT && b.kind == RoadNodeKind.ENDPOINT) {
            return a.aliveChains(chains) == 1 && b.aliveChains(chains) == 1 ? params.minSpurLength() : -1;
        }
        WorkNode end = a.kind == RoadNodeKind.ENDPOINT ? a : b.kind == RoadNodeKind.ENDPOINT ? b : null;
        WorkNode junction = end == a ? b : a;
        if (end == null || junction.kind != RoadNodeKind.JUNCTION) {
            return -1;
        }
        return Math.max(params.minSpurLength(), medianWidth(chain));
    }

    /**
     * The road's own width along a chain (median of {@code 2·dt − 1} over its spans): a fork spur at
     * a wide road's end runs through narrow border spans, a real arm keeps the road's width. The
     * junction span itself is left out — its dt is inflated by the crossing.
     */
    private int medianWidth(WorkChain chain) {
        int[] spans = chain.spans;
        int from = nodes.get(chain.from).kind == RoadNodeKind.JUNCTION ? 1 : 0;
        int to = nodes.get(chain.to).kind == RoadNodeKind.JUNCTION ? spans.length - 1 : spans.length;
        if (to <= from) {
            return DistanceTransform.width(dt[spans[0]]);
        }
        int[] widths = new int[to - from];
        for (int k = from; k < to; k++) {
            widths[k - from] = DistanceTransform.width(dt[spans[k]]);
        }
        Arrays.sort(widths);
        return widths[widths.length / 2];
    }

    private void removeChain(int id) {
        WorkChain chain = chains.get(id);
        chain.alive = false;
        for (int nodeId : new int[] {chain.from, chain.to}) {
            WorkNode node = nodes.get(nodeId);
            int remaining = node.aliveChains(chains);
            if (remaining == 0) {
                killNode(nodeId);
            } else if (node.kind == RoadNodeKind.JUNCTION) {
                if (remaining == 1) {
                    node.kind = RoadNodeKind.ENDPOINT;
                } else if (remaining == 2) {
                    dissolve(nodeId);
                }
            }
        }
    }

    private void killNode(int nodeId) {
        WorkNode node = nodes.get(nodeId);
        node.alive = false;
        for (int i = 0; i < nodeOf.length; i++) {
            if (nodeOf[i] == nodeId) {
                nodeOf[i] = -1;
            }
        }
    }

    /** A junction with exactly two chains becomes a plain point on one merged chain. */
    private void dissolve(int nodeId) {
        WorkNode node = nodes.get(nodeId);
        List<Integer> alive = new ArrayList<>();
        for (int id : node.chainIds) {
            if (chains.get(id).alive) {
                alive.add(id);
            }
        }
        WorkChain first = chains.get(alive.get(0));
        WorkChain second = chains.get(alive.get(1));
        int[] a = orientedTowards(first, nodeId);      // ... → node span
        int[] b = orientedAwayFrom(second, nodeId);    // node span → ...
        int otherA = first.from == nodeId ? first.to : first.from;
        int otherB = second.from == nodeId ? second.to : second.from;
        first.alive = false;
        second.alive = false;
        killNode(nodeId);
        // Join the two chains at their spans next to the node, through the straightest mask span
        // between them (often the road cell thinning removed, not the stub's first span).
        int[] merged;
        if (a.length >= 2 && b.length >= 2 && a[a.length - 1] == b[0]) {
            int s1 = a[a.length - 2];
            int s2 = b[1];
            int[] head = Arrays.copyOf(a, a.length - 1);
            int[] tail = Arrays.copyOfRange(b, 1, b.length);
            int bridge = Thinning.linked(mask, s1, s2) ? RoadMask.NONE : bridgeSpan(s1, s2, a[a.length - 1]);
            merged = concat(head, bridge, tail);
        } else {
            int skip = a[a.length - 1] == b[0] ? 1 : 0;
            merged = concat(a, RoadMask.NONE, Arrays.copyOfRange(b, skip, b.length));
        }
        for (int span : merged) {
            skeleton[span] = true;
        }
        addChain(otherA, otherB, merged);
    }

    /** The common mask neighbour of two spans with the shortest way through it; {@code fallback} if none. */
    private int bridgeSpan(int s1, int s2, int fallback) {
        int best = fallback;
        double bestLength = mask.distance(s1, fallback) + mask.distance(fallback, s2);
        for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
            int m = mask.neighbour(s1, d);
            if (m == RoadMask.NONE || m == fallback || !Thinning.linked(mask, m, s2)) {
                continue;
            }
            double length = mask.distance(s1, m) + mask.distance(m, s2);
            if (length < bestLength - 1e-9) {
                bestLength = length;
                best = m;
            }
        }
        return best;
    }

    private static int[] concat(int[] head, int bridge, int[] tail) {
        int extra = bridge == RoadMask.NONE ? 0 : 1;
        int[] out = new int[head.length + extra + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        if (extra == 1) {
            out[head.length] = bridge;
        }
        System.arraycopy(tail, 0, out, head.length + extra, tail.length);
        return out;
    }

    private static int[] orientedTowards(WorkChain chain, int nodeId) {
        if (chain.to == nodeId) {
            return chain.spans;
        }
        return reversed(chain.spans);
    }

    private static int[] orientedAwayFrom(WorkChain chain, int nodeId) {
        if (chain.from == nodeId) {
            return chain.spans;
        }
        return reversed(chain.spans);
    }

    private static int[] reversed(int[] spans) {
        int[] out = new int[spans.length];
        for (int i = 0; i < spans.length; i++) {
            out[i] = spans[spans.length - 1 - i];
        }
        return out;
    }

    // ---- 7. loops and parallel chains --------------------------------------------------------

    private void splitLoopsAndParallels() {
        for (int round = 0; round < MAX_SPLIT_ROUNDS; round++) {
            boolean changed = false;
            Set<Long> pairs = new HashSet<>();
            for (int id = 0; id < chains.size(); id++) {
                WorkChain chain = chains.get(id);
                if (!chain.alive) {
                    continue;
                }
                boolean loop = chain.from == chain.to;
                boolean duplicate = !loop && !pairs.add(pairKey(chain.from, chain.to));
                if (!loop && !duplicate) {
                    continue;
                }
                int parts = loop ? 3 : 2;
                if (chain.spans.length < parts + 1) {
                    chain.alive = false; // a duplicate too short to split: the other chain carries the pair
                    changed = true;
                    continue;
                }
                splitChain(id, parts);
                changed = true;
            }
            if (!changed) {
                return;
            }
        }
    }

    private void splitChain(int id, int parts) {
        WorkChain chain = chains.get(id);
        chain.alive = false;
        int[] spans = chain.spans;
        int[] cuts = new int[parts + 1];
        cuts[0] = 0;
        cuts[parts] = spans.length - 1;
        for (int p = 1; p < parts; p++) {
            cuts[p] = (int) Math.round((double) p * (spans.length - 1) / parts);
        }
        int[] nodeIds = new int[parts + 1];
        nodeIds[0] = chain.from;
        nodeIds[parts] = chain.to;
        for (int p = 1; p < parts; p++) {
            int span = spans[cuts[p]];
            nodeIds[p] = nodeOf[span] >= 0 ? nodeOf[span] : newNode(span, RoadNodeKind.JUNCTION);
        }
        for (int p = 0; p < parts; p++) {
            int[] part = Arrays.copyOfRange(spans, cuts[p], cuts[p + 1] + 1);
            addChain(nodeIds[p], nodeIds[p + 1], part);
        }
    }

    // ---- 8. tile border ----------------------------------------------------------------------

    private boolean inside(int span) {
        return tile.contains(mask.x(span), mask.z(span));
    }

    private boolean nodeInside(int nodeId) {
        WorkNode node = nodes.get(nodeId);
        return tile.contains(node.x, node.z);
    }

    private void cutAtTileBorder() {
        int chainCount = chains.size();
        Map<Integer, Integer> boundaryBySpan = new HashMap<>();
        for (int id = 0; id < chainCount; id++) {
            WorkChain chain = chains.get(id);
            if (!chain.alive) {
                continue;
            }
            int[] spans = chain.spans;
            boolean fromInside = nodeInside(chain.from);
            boolean toInside = nodeInside(chain.to);
            boolean allInside = fromInside && toInside;
            for (int span : spans) {
                if (!inside(span)) {
                    allInside = false;
                    break;
                }
            }
            if (allInside) {
                continue;
            }
            chain.alive = false;
            int k = 0;
            while (k < spans.length) {
                while (k < spans.length && !inside(spans[k])) {
                    k++;
                }
                if (k >= spans.length) {
                    break;
                }
                int start = k;
                while (k < spans.length && inside(spans[k])) {
                    k++;
                }
                int end = k - 1;
                boolean startIsNode = start == 0 && fromInside;
                boolean endIsNode = end == spans.length - 1 && toInside;
                if (start == end && !startIsNode && !endIsNode) {
                    continue; // clips a single border span: nothing to keep
                }
                int fromNode = startIsNode ? chain.from : boundaryNode(spans[start], boundaryBySpan);
                int toNode = endIsNode ? chain.to : boundaryNode(spans[end], boundaryBySpan);
                if (fromNode == toNode && start == end) {
                    continue;
                }
                addChain(fromNode, toNode, Arrays.copyOfRange(spans, start, end + 1));
            }
        }
        // Nodes outside the tile (and everything else without a live chain) are dropped in emit().
        for (int id = 0; id < nodes.size(); id++) {
            if (nodes.get(id).alive && !nodeInside(id)) {
                nodes.get(id).alive = false;
            }
        }
    }

    private int boundaryNode(int span, Map<Integer, Integer> boundaryBySpan) {
        if (nodeOf[span] >= 0 && nodes.get(nodeOf[span]).alive && nodeInside(nodeOf[span])) {
            WorkNode node = nodes.get(nodeOf[span]);
            if (node.kind == RoadNodeKind.ENDPOINT) {
                node.kind = RoadNodeKind.BOUNDARY;
            }
            return nodeOf[span];
        }
        Integer existing = boundaryBySpan.get(span);
        if (existing != null) {
            return existing;
        }
        int id = newNode(span, RoadNodeKind.BOUNDARY);
        boundaryBySpan.put(span, id);
        return id;
    }

    // ---- 9. output ---------------------------------------------------------------------------

    private Result emit() {
        int[] remap = new int[nodes.size()];
        Arrays.fill(remap, -1);
        List<Node> outNodes = new ArrayList<>();
        for (int id = 0; id < nodes.size(); id++) {
            WorkNode node = nodes.get(id);
            if (!node.alive || node.aliveChains(chains) == 0) {
                continue;
            }
            remap[id] = outNodes.size();
            outNodes.add(new Node(outNodes.size(), node.x, node.y, node.z, node.kind,
                node.anchorId >= 0 ? OptionalInt.of(node.anchorId) : OptionalInt.empty(), node.span));
        }
        List<Chain> outChains = new ArrayList<>();
        for (WorkChain chain : chains) {
            if (!chain.alive) {
                continue;
            }
            int from = remap[chain.from];
            int to = remap[chain.to];
            if (from < 0 || to < 0) {
                continue;
            }
            outChains.add(new Chain(from, to, chain.spans.clone()));
        }
        return new Result(Collections.unmodifiableList(outNodes), Collections.unmodifiableList(outChains),
            Collections.unmodifiableList(new ArrayList<>(warnings)));
    }
}

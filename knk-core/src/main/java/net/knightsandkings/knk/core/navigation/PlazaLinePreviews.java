package net.knightsandkings.knk.core.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.navigation.shape.NavWorld;
import net.knightsandkings.knk.core.navigation.shape.PlazaCrossing;
import net.knightsandkings.knk.core.navigation.shape.PlazaCurve;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/**
 * What the staff preview of plaza lines works out ({@code /knk road plazaline}, off-road navigation phase 7 step 1,
 * DESIGN § 8.9, O16; PandiO/knk-workspace#712): the developer sees the lines on real plazas before the trail uses them.
 * Two kinds of passes:
 * <ol>
 *     <li>Through a named node: every pair of the roads that meet there, each as a line from one road into the node
 *     and out along the other ({@link #throughNode}). A node with roads A, B and C gives A→B, A→C and B→C.</li>
 *     <li>Along the player's route: one pass per junction of the route that may be a plaza ({@link #alongRoute}).</li>
 * </ol>
 * Each pass is worked out by {@link PlazaCurve#cross} ({@link #compute}). Bukkit-free; the caller draws.
 */
public final class PlazaLinePreviews {

    private PlazaLinePreviews() {
    }

    /**
     * One route through one junction for the preview.
     *
     * @param label what the developer reads ("Brink: Keepstreet → Market Lane")
     * @param feet  the route as feet positions (block centres, a block above the floor)
     * @param plaza the junction
     */
    public record Pass(String label, List<NavPoint> feet, PlazaCrossing plaza) {
        public Pass {
            Objects.requireNonNull(label, "label");
            feet = List.copyOf(feet);
            Objects.requireNonNull(plaza, "plaza");
        }
    }

    /** A pass with its plaza line worked out. */
    public record Line(Pass pass, PlazaCurve.Result result) {
    }

    /**
     * Road points (floor blocks, as the road graph and the router give them) as feet positions: the block's centre, a
     * block above the floor, as the NPC route follower stands on them.
     */
    public static List<NavPoint> feetOf(String world, List<double[]> floorPoints) {
        List<NavPoint> out = new ArrayList<>(floorPoints.size());
        for (double[] p : floorPoints) {
            out.add(new NavPoint(world, Math.floor(p[0]) + 0.5, Math.floor(p[1]) + 1, Math.floor(p[2]) + 0.5));
        }
        return out;
    }

    /** A node's junction for the plaza line: its floor point, with its designed plaza radius (0 to measure). */
    public static PlazaCrossing crossingOf(String world, RoadNode node) {
        return new PlazaCrossing(new NavPoint(world, node.x(), node.y(), node.z()), node.plazaRadius());
    }

    /**
     * The node a staff member names: its id, or its name (any case); of several nodes with that name the one nearest
     * {@code (x, z)}. Empty when none matches.
     */
    public static Optional<RoadNode> findNode(RoadNetworkSnapshot snapshot, String idOrName, double x, double z) {
        String wanted = idOrName == null ? "" : idOrName.trim();
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        if (wanted.startsWith("#")) {
            wanted = wanted.substring(1);
        }
        try {
            int id = Integer.parseInt(wanted);
            Optional<RoadNode> byId = snapshot.node(id);
            if (byId.isPresent()) {
                return byId;
            }
        } catch (NumberFormatException ignored) {
            // a name
        }
        RoadNode best = null;
        double bestD = Double.MAX_VALUE;
        for (RoadNode node : snapshot.nodes()) {
            if (node.name() != null && node.name().equalsIgnoreCase(wanted)) {
                double d = Math.hypot(node.x() + 0.5 - x, node.z() + 0.5 - z);
                if (d < bestD) {
                    bestD = d;
                    best = node;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Every pair of the roads meeting at {@code node}, each as a pass that comes in along the first road (its last
     * {@code reach} blocks before the node) and leaves along the second (its first {@code reach} blocks). Loops (an
     * edge from the node back to it) are left out. Fewer than two roads: none.
     */
    public static List<Pass> throughNode(RoadNetworkSnapshot snapshot, RoadNode node, double reach) {
        String world = snapshot.world();
        List<RoadEdge> edges = new ArrayList<>();
        for (RoadEdge e : snapshot.edgesAt(node.id())) {
            if (e.fromNodeId() != e.toNodeId() && e.geometry().size() >= 2) {
                edges.add(e);
            }
        }
        PlazaCrossing plaza = crossingOf(world, node);
        String nodeName = nameOf(node);
        List<Pass> out = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            for (int j = i + 1; j < edges.size(); j++) {
                RoadEdge a = edges.get(i);
                RoadEdge b = edges.get(j);
                List<double[]> in = towards(snapshot, a, node.id(), reach);
                List<double[]> outOf = towards(snapshot, b, node.id(), reach);
                Collections.reverse(outOf);
                List<double[]> line = new ArrayList<>(in);
                line.addAll(outOf.subList(1, outOf.size())); // both end at the node's own point
                String label = nodeName + ": " + roadName(snapshot, a) + " → " + roadName(snapshot, b);
                out.add(new Pass(label, feetOf(world, line), plaza));
            }
        }
        return out;
    }

    /**
     * The route's junctions that may be plazas, one pass each over the whole route polyline ({@code polyline}: floor
     * points, as {@code Route.polyline()} gives them).
     */
    public static List<Pass> alongRoute(String world, List<double[]> polyline, List<PlazaCrossing> crossings,
                                        Function<PlazaCrossing, String> label) {
        List<NavPoint> feet = feetOf(world, polyline);
        List<Pass> out = new ArrayList<>();
        for (PlazaCrossing c : crossings) {
            out.add(new Pass(label.apply(c), feet, c));
        }
        return out;
    }

    /** Each pass's plaza line on {@code world} (main thread for a live world). */
    public static List<Line> compute(NavWorld world, List<Pass> passes, PlazaCurve.Options options) {
        List<Line> out = new ArrayList<>(passes.size());
        for (Pass p : passes) {
            out.add(new Line(p, PlazaCurve.cross(world, p.feet(), p.plaza(), options)));
        }
        return out;
    }

    /**
     * One line of chat for the developer: what came out, its length against the chord and the right angle at the node
     * (the route polyline's own length between the same two points), its least clearance, and why the curve was
     * rejected when it was.
     */
    public static String describe(Line line) {
        PlazaCurve.Result r = line.result();
        String label = line.pass().label();
        return switch (r.kind()) {
            case CURVE -> String.format(Locale.ROOT, "%s: curve %.1f blocks (chord %.1f, right angle %.1f, %s), "
                + "clearance at least %.2f", label, r.lineLength(), r.chordLength(), r.routeLength(),
                saving(r), r.minClearance());
            case GUIDED -> String.format(Locale.ROOT, "%s: A* line %.1f blocks (chord %.1f, right angle %.1f, %s), "
                + "clearance at least %.2f; the curve did not fit: %s", label, r.lineLength(), r.chordLength(),
                r.routeLength(), saving(r), r.minClearance(), r.curveFailure());
            case NONE -> String.format(Locale.ROOT, "%s: no plaza line, the route keeps its bend (right angle %.1f); "
                + "the curve did not fit: %s; the A* found nothing", label, r.routeLength(), r.curveFailure());
            case STREET -> label + ": a street junction (open floor no wider than the corridor), no plaza line";
            case NOT_CROSSED -> label + ": the route does not cross this plaza (it starts or ends on it)";
        };
    }

    private static String saving(PlazaCurve.Result r) {
        if (!(r.routeLength() > 0)) {
            return "0 %";
        }
        long pct = Math.round(100 * (r.lineLength() - r.routeLength()) / r.routeLength());
        return (pct > 0 ? "+" : "") + pct + " %";
    }

    /**
     * The road along {@code edge} ending at {@code nodeId}, its last {@code reach} blocks: when the edge is shorter, on
     * along the next edge beyond its far node while that node joins exactly two edges (a split or a bend, not a
     * junction).
     */
    static List<double[]> towards(RoadNetworkSnapshot snapshot, RoadEdge edge, int nodeId, double reach) {
        List<double[]> pts = oriented(edge, nodeId);
        double length = length(pts);
        int far = edge.toNodeId() == nodeId ? edge.fromNodeId() : edge.toNodeId();
        RoadEdge current = edge;
        java.util.Set<Integer> seen = new java.util.HashSet<>(List.of(edge.id()));
        while (length < reach && far != nodeId) {
            List<RoadEdge> at = new ArrayList<>(snapshot.edgesAt(far));
            RoadEdge from = current;
            at.removeIf(e -> e.id() == from.id());
            if (at.size() != 1 || !seen.add(at.get(0).id()) || at.get(0).geometry().size() < 2) {
                break;
            }
            RoadEdge next = at.get(0);
            List<double[]> more = oriented(next, far);
            List<double[]> joined = new ArrayList<>(more.subList(0, more.size() - 1));
            joined.addAll(pts);
            pts = joined;
            length += length(more);
            far = next.toNodeId() == far ? next.fromNodeId() : next.toNodeId();
            current = next;
        }
        // keep the points within reach (along the road) of its end at the node
        double along = 0;
        int from = pts.size() - 1;
        for (int k = pts.size() - 1; k > 0; k--) {
            double[] p = pts.get(k);
            double[] q = pts.get(k - 1);
            along += Math.hypot(p[0] - q[0], p[2] - q[2]);
            from = k - 1;
            if (along >= reach) {
                break;
            }
        }
        return new ArrayList<>(pts.subList(from, pts.size()));
    }

    /** The edge's geometry as points, in the order that ends at {@code nodeId}. */
    private static List<double[]> oriented(RoadEdge edge, int nodeId) {
        List<double[]> pts = new ArrayList<>();
        for (int[] p : edge.geometry()) {
            pts.add(new double[] {p[0], p[1], p[2]});
        }
        if (edge.toNodeId() != nodeId) {
            Collections.reverse(pts);
        }
        return pts;
    }

    private static double length(List<double[]> pts) {
        double total = 0;
        for (int k = 1; k < pts.size(); k++) {
            total += Math.hypot(pts.get(k)[0] - pts.get(k - 1)[0], pts.get(k)[2] - pts.get(k - 1)[2]);
        }
        return total;
    }

    private static String nameOf(RoadNode node) {
        return node.name() == null || node.name().isBlank() ? "node #" + node.id() : node.name() + " (#" + node.id() + ")";
    }

    private static String roadName(RoadNetworkSnapshot snapshot, RoadEdge edge) {
        return snapshot.streetOf(edge).map(name -> name + " (edge #" + edge.id() + ")").orElse("edge #" + edge.id());
    }
}

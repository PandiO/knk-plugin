package net.knightsandkings.knk.core.npc.nav.snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavWorld;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner;
import net.knightsandkings.knk.core.npc.nav.RouteFollower;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/**
 * The engine-neutral pieces of a snapshot (link B1, PandiO/knk-workspace#670): the route line from a road plan, its
 * stretches as the route follower hands them out, and the road graph inside the box; and back from a snapshot to the
 * points the replay plans on.
 */
public final class SnapshotParts {

    /** At most this many stretches are cut (a line of about 4 km at 32 blocks each): a guard against a loop. */
    static final int MAX_STRETCHES = 4096;

    private SnapshotParts() {
    }

    /** A point as {x, y, z}. */
    public static double[] xyz(NavPoint p) {
        return new double[] {p.x(), p.y(), p.z()};
    }

    /** {x, y, z} as a point in {@code world}. */
    public static NavPoint point(String world, double[] xyz) {
        return new NavPoint(world, xyz[0], xyz[1], xyz[2]);
    }

    /** The stored route line as points in {@code world} (floor points). */
    public static List<NavPoint> line(NavSnapshot.RouteLine route, String world) {
        List<NavPoint> out = new ArrayList<>(route.line().size());
        for (double[] p : route.line()) {
            out.add(point(world, p));
        }
        return out;
    }

    /** The route part of a snapshot from a road plan. */
    public static NavSnapshot.RouteLine route(String name, NavPoint from, NavPoint to, RoadPlanner.RoadPlan plan,
                                              List<List<double[]>> stretches, String stretchesNote) {
        List<double[]> line = new ArrayList<>(plan.line().size());
        for (NavPoint p : plan.line()) {
            line.add(xyz(p));
        }
        List<NavSnapshot.GateCrossing> gates = new ArrayList<>();
        for (RoadPlanner.GateCrossing gate : plan.gates()) {
            gates.add(new NavSnapshot.GateCrossing(gate.doorId(), xyz(gate.at())));
        }
        List<NavSnapshot.Plaza> plazas = new ArrayList<>();
        for (RoadPlanner.PlazaCrossing plaza : plan.plazas()) {
            plazas.add(new NavSnapshot.Plaza(xyz(plaza.centre()), plaza.radius()));
        }
        return new NavSnapshot.RouteLine(name, xyz(from), xyz(to), plan.status().name(), plan.reason(), line,
            stretches, stretchesNote, gates, plazas);
    }

    /**
     * The stretches of a line as {@link RouteFollower} hands them out to an NPC that starts at the line's first point
     * and keeps up (each stretch continuing from the last), with {@code stretchBlocks} and {@code cornerClearance}
     * (corridor steering is not applied: the replay's own settings decide that). Stops at a chunk that is not
     * loaded, with a note in {@code notes}.
     */
    public static List<List<double[]>> stretches(List<NavPoint> line, List<RoadPlanner.PlazaCrossing> plazas,
                                                 NavWorld world, double stretchBlocks, double cornerClearance,
                                                 List<String> notes) {
        List<List<double[]>> out = new ArrayList<>();
        if (line.size() < 2) {
            return out;
        }
        RouteFollower follower = new RouteFollower(line, plazas);
        int floorY = line.get(0).blockY();
        while (follower.hasMore() && out.size() < MAX_STRETCHES) {
            RouteFollower.Stretch stretch = follower.next(world, stretchBlocks, floorY, cornerClearance);
            if (stretch.kind() == RouteFollower.Kind.UNLOADED) {
                notes.add("stretches stop after " + out.size() + ": the next one reaches a chunk that is not loaded");
                break;
            }
            if (stretch.kind() == RouteFollower.Kind.DONE) {
                break;
            }
            List<double[]> points = new ArrayList<>(stretch.waypoints().size());
            for (NavPoint p : stretch.waypoints()) {
                points.add(xyz(p));
            }
            out.add(points);
            if (!stretch.waypoints().isEmpty()) {
                floorY = stretch.waypoints().get(stretch.waypoints().size() - 1).blockY() - 1;
            }
        }
        return out;
    }

    /**
     * The road graph inside the box: every node in it, and every edge with a geometry point in it (whole, with both
     * end nodes), in id order.
     */
    public static NavSnapshot.Roads roads(RoadNetworkSnapshot network, SnapshotBox box) {
        if (network == null) {
            return new NavSnapshot.Roads(List.of(), List.of());
        }
        java.util.Map<Integer, NavSnapshot.Node> nodes = new java.util.TreeMap<>();
        List<NavSnapshot.Edge> edges = new ArrayList<>();
        for (RoadNode node : network.nodes()) {
            if (box.contains(node.x(), node.y(), node.z())) {
                nodes.put(node.id(), node(node));
            }
        }
        for (RoadEdge edge : network.edges()) {
            boolean inside = false;
            for (int[] p : edge.geometry()) {
                if (box.contains(p[0], p[1], p[2])) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                continue;
            }
            network.node(edge.fromNodeId()).ifPresent(n -> nodes.putIfAbsent(n.id(), node(n)));
            network.node(edge.toNodeId()).ifPresent(n -> nodes.putIfAbsent(n.id(), node(n)));
            List<String> flags = edge.flags().stream().map(Enum::name).sorted().toList();
            edges.add(new NavSnapshot.Edge(edge.id(), edge.fromNodeId(), edge.toNodeId(), edge.geometry(), edge.length(),
                edge.avgWidth(), network.roadClass(edge).map(c -> c.name().toLowerCase(Locale.ROOT)).orElse(null), flags,
                edge.gateDoorIds(), edge.regionIds(), edge.domainIds()));
        }
        edges.sort(Comparator.comparingInt(NavSnapshot.Edge::id));
        return new NavSnapshot.Roads(new ArrayList<>(nodes.values()), edges);
    }

    private static NavSnapshot.Node node(RoadNode node) {
        return new NavSnapshot.Node(node.id(), node.x(), node.y(), node.z(), node.kind().name(), node.name(),
            node.plazaRadius());
    }
}

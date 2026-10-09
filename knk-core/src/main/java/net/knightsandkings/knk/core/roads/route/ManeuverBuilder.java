package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a {@link Route} into instructions (DESIGN §6.5). At every {@code Junction} on the route, or
 * wherever the street label changes, the heading change Δ = bearing after − before (each measured
 * over {@value #BEARING_WINDOW} blocks of the route polyline): {@code < 20°} straight (announced
 * only on a street change, "Continue onto Merchantstreet"), 20-60° slight, 60-120° turn,
 * {@code > 120°} sharp; left/right from the sign (x east, z south: a positive cross product is a
 * right turn). Level changes at any node: when the next edge drops more than {@value #LEVEL_DELTA}
 * blocks below the node, "Go down into the tunnel"; when it climbs more than that and comes back
 * down by its end, "Cross the bridge", otherwise "Take the stairs up" (Phase 2d decision).
 * {@code Boundary} nodes and stitch edges are plumbing and never produce a maneuver; the street
 * comparison looks through them. So are the routing view's {@code Split} nodes (rev. 7 Part A): the level
 * check at a node looks through them to the end of the stored edge, as before the view cut it. Unlabelled next edges say "Take the path on the left" (path
 * class) or "Take the road on the left".
 */
public final class ManeuverBuilder {

    /** Blocks of route before/after a node the bearings are measured over. */
    public static final double BEARING_WINDOW = 6;
    /** Height difference (blocks) from which a level-change phrase is added. */
    public static final double LEVEL_DELTA = 3;
    public static final double STRAIGHT_DEGREES = 20;
    public static final double SLIGHT_DEGREES = 60;
    public static final double TURN_DEGREES = 120;

    private final RoadNetworkSnapshot snapshot;

    public ManeuverBuilder(RoadNetworkSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public List<Maneuver> build(Route route) {
        List<Maneuver> out = new ArrayList<>();
        List<Route.Step> steps = route.steps();
        if (steps.size() < 2) {
            return out;
        }
        double along = 0; // polyline distance at the boundary before step i
        Optional<String> lastStreet = streetOf(steps.get(0));
        for (int i = 0; i < steps.size() - 1; i++) {
            Route.Step current = steps.get(i);
            Route.Step next = steps.get(i + 1);
            along += Math.abs(current.exitAlong() - current.entryAlong());
            if (!current.edge().isStitch()) {
                lastStreet = streetOf(current); // the street comparison looks through stitch edges
            }
            int nodeId = current.exitNode(snapshot.polyline(current.edge()));
            if (nodeId < 0 || next.edge().isStitch()) {
                continue;
            }
            RoadNode node = snapshot.requireNode(nodeId);
            if (node.kind() == RoadNodeKind.BOUNDARY || node.kind() == RoadNodeKind.SPLIT) {
                continue;
            }
            Optional<String> nextStreet = streetOf(next);
            boolean streetChanged = !nextStreet.equals(lastStreet);
            double bearing = bearingChange(route, along);
            Maneuver.Kind kind = turnKind(bearing);
            boolean junction = node.kind() == RoadNodeKind.JUNCTION;
            if (kind != null && (junction || streetChanged)) {
                out.add(new Maneuver(kind, node.position(), along, bearing, nextStreet.orElse(null),
                    turnText(kind, next.edge(), nextStreet, streetChanged)));
            } else if (streetChanged && nextStreet.isPresent()) {
                out.add(new Maneuver(Maneuver.Kind.CONTINUE, node.position(), along, bearing, nextStreet.get(),
                    "Continue onto " + nextStreet.get()));
            }
            Optional<Maneuver.Kind> level = levelChange(throughSplits(steps, i + 1), node.y());
            if (level.isPresent()) {
                out.add(new Maneuver(level.get(), node.position(), along, 0, nextStreet.orElse(null),
                    levelText(level.get())));
            }
        }
        return out;
    }

    private Optional<String> streetOf(Route.Step step) {
        return snapshot.streetOf(step.edge());
    }

    /** Signed heading change at {@code along}, degrees, positive = right; 0 when the route is too short to tell. */
    double bearingChange(Route route, double along) {
        double[] before = route.pointAt(Math.max(0, along - BEARING_WINDOW));
        double[] at = route.pointAt(along);
        double[] after = route.pointAt(Math.min(route.polylineLength(), along + BEARING_WINDOW));
        double bx = at[0] - before[0], bz = at[2] - before[2];
        double ax = after[0] - at[0], az = after[2] - at[2];
        if ((bx == 0 && bz == 0) || (ax == 0 && az == 0)) {
            return 0;
        }
        double cross = bx * az - bz * ax;
        double dot = bx * ax + bz * az;
        return Math.toDegrees(Math.atan2(cross, dot));
    }

    /** The turn band, or {@code null} for straight. */
    static Maneuver.Kind turnKind(double bearing) {
        double abs = Math.abs(bearing);
        boolean right = bearing > 0;
        if (abs < STRAIGHT_DEGREES) {
            return null;
        }
        if (abs < SLIGHT_DEGREES) {
            return right ? Maneuver.Kind.SLIGHT_RIGHT : Maneuver.Kind.SLIGHT_LEFT;
        }
        if (abs <= TURN_DEGREES) {
            return right ? Maneuver.Kind.RIGHT : Maneuver.Kind.LEFT;
        }
        return right ? Maneuver.Kind.SHARP_RIGHT : Maneuver.Kind.SHARP_LEFT;
    }

    private String turnText(Maneuver.Kind kind, RoadEdge next, Optional<String> street, boolean streetChanged) {
        String side = kind == Maneuver.Kind.LEFT || kind == Maneuver.Kind.SLIGHT_LEFT || kind == Maneuver.Kind.SHARP_LEFT
            ? "left" : "right";
        String turn = switch (kind) {
            case SLIGHT_LEFT, SLIGHT_RIGHT -> "Turn slightly " + side;
            case SHARP_LEFT, SHARP_RIGHT -> "Turn sharply " + side;
            default -> "Turn " + side;
        };
        if (street.isPresent()) {
            return streetChanged ? turn + " onto " + street.get() : turn + " to stay on " + street.get();
        }
        String what = snapshot.roadClass(next).filter(c -> c == RoadClass.PATH).isPresent() ? "path" : "road";
        return "Take the " + what + " on the " + side;
    }

    /** Step {@code first} and the steps after it that continue through a {@code Split} node. */
    private List<Route.Step> throughSplits(List<Route.Step> steps, int first) {
        List<Route.Step> out = new ArrayList<>();
        for (int i = first; i < steps.size(); i++) {
            Route.Step step = steps.get(i);
            out.add(step);
            int exit = step.exitNode(snapshot.polyline(step.edge()));
            if (exit < 0 || i + 1 >= steps.size() || snapshot.requireNode(exit).kind() != RoadNodeKind.SPLIT) {
                break;
            }
        }
        return out;
    }

    /** DOWN / UP / BRIDGE when the next step's stretch leaves the node's level by more than {@link #LEVEL_DELTA}. */
    Optional<Maneuver.Kind> levelChange(Route.Step next, double nodeY) {
        return levelChange(List.of(next), nodeY);
    }

    /** {@link #levelChange(Route.Step, double)} over consecutive steps (one stored edge cut by the routing view). */
    Optional<Maneuver.Kind> levelChange(List<Route.Step> next, double nodeY) {
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Route.Step step : next) {
            for (double[] pt : snapshot.polyline(step.edge()).subPolyline(step.entryAlong(), step.exitAlong())) {
                minY = Math.min(minY, pt[1]);
                maxY = Math.max(maxY, pt[1]);
            }
        }
        Route.Step last = next.get(next.size() - 1);
        double exitY = snapshot.polyline(last.edge()).pointAt(last.exitAlong())[1];
        if (minY < nodeY - LEVEL_DELTA) {
            return Optional.of(Maneuver.Kind.DOWN);
        }
        if (maxY > nodeY + LEVEL_DELTA) {
            return Optional.of(exitY <= nodeY + LEVEL_DELTA ? Maneuver.Kind.BRIDGE : Maneuver.Kind.UP);
        }
        return Optional.empty();
    }

    static String levelText(Maneuver.Kind kind) {
        return switch (kind) {
            case DOWN -> "Go down into the tunnel";
            case UP -> "Take the stairs up";
            case BRIDGE -> "Cross the bridge";
            default -> throw new IllegalArgumentException(kind + " is not a level change");
        };
    }
}

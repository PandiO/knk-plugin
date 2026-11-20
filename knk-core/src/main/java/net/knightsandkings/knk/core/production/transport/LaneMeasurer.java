package net.knightsandkings.knk.core.production.transport;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import net.knightsandkings.knk.core.roads.route.AStarRouter;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.Route;
import net.knightsandkings.knk.core.roads.route.RouteRequest;
import net.knightsandkings.knk.core.roads.route.RouteResult;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import net.knightsandkings.knk.core.roads.route.Snapper;

/**
 * The lane measurer (DESIGN § 7.6, PandiO/knk-workspace#681): the road route between two structures with
 * {@link AStarRouter} over the plugin's road snapshot, used read-only. Pure and thread-safe; it runs off the main thread.
 * <ol>
 *   <li>Each position joins the road at the nearest road point within {@code maxSnapDistance} blocks (3D; the height only
 *       ranks the candidates, as navigation does). A structure with no road that near is not on the network:
 *       unreachable.</li>
 *   <li>The route is searched in the default mode with every gate and region open ({@link AccessPolicy#ALL_OPEN}): the
 *       carriers are simulated workers (G14), not players with access rules. One-way roads still count.</li>
 *   <li>The road distance is the walk to the road + the road route + the walk from the road; the straight distance is
 *       the 3D distance between the positions.</li>
 *   <li>Different worlds, different road components, or no route: unreachable.</li>
 *   <li>Not measured (skipped, nothing reported): a structure without a position, or a world whose road snapshot is not
 *       loaded or empty. A missing snapshot must never read as "no road" for every structure.</li>
 * </ol>
 */
public final class LaneMeasurer {

    /** A measured lane, or the reason it was not measured ({@code measurement} null). */
    public record Outcome(LaneDue due, LaneMeasurement measurement, String skipped) {
        public boolean measured() {
            return measurement != null;
        }
    }

    private final Function<String, RoadNetworkSnapshot> snapshots;
    private final Supplier<RouterParameters> parameters;
    private final double maxSnapDistance;
    private final Clock clock;

    /**
     * @param snapshots the road snapshot per world name (null or empty when none is loaded)
     */
    public LaneMeasurer(Function<String, RoadNetworkSnapshot> snapshots, RouterParameters parameters,
                        double maxSnapDistance, Clock clock) {
        this(snapshots, () -> parameters, maxSnapDistance, clock);
    }

    /**
     * @param snapshots  the road snapshot per world name (null or empty when none is loaded)
     * @param parameters the router's parameters (class costs, snap weight), read per lane; null or failing = defaults
     */
    public LaneMeasurer(Function<String, RoadNetworkSnapshot> snapshots, Supplier<RouterParameters> parameters,
                        double maxSnapDistance, Clock clock) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.parameters = parameters == null ? RouterParameters::defaults : parameters;
        this.maxSnapDistance = maxSnapDistance;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Outcome measure(LaneDue due) {
        LanePosition from = due.from();
        LanePosition to = due.to();
        if (from == null || to == null || from.world() == null || to.world() == null) {
            return skipped(due, (from == null || from.world() == null ? "#" + due.fromStructureId()
                    : "#" + due.toStructureId()) + " has no location");
        }
        if (!from.sameWorld(to)) {
            return unreachable(due, null, "the structures are in different worlds");
        }
        double straight = from.distanceTo(to);
        RoadNetworkSnapshot snapshot = snapshots.apply(from.world());
        if (snapshot == null || snapshot.isEmpty()) {
            return skipped(due, "no road network loaded for world " + from.world());
        }
        RouterParameters routerParameters = parameters();
        Optional<SnapPoint> start = snap(snapshot, from, routerParameters);
        if (start.isEmpty()) {
            return unreachable(due, straight, "#" + due.fromStructureId() + " is more than "
                    + Math.round(maxSnapDistance) + " blocks from a road");
        }
        Optional<SnapPoint> goal = snap(snapshot, to, routerParameters);
        if (goal.isEmpty()) {
            return unreachable(due, straight, "#" + due.toStructureId() + " is more than "
                    + Math.round(maxSnapDistance) + " blocks from a road");
        }
        RouteResult result = new AStarRouter(snapshot).route(
                new RouteRequest(start.get(), List.of(goal.get()), AccessPolicy.ALL_OPEN, routerParameters.classCost()));
        if (result.status() != RouteResult.Status.FOUND || result.route() == null) {
            return unreachable(due, straight, result.status() == RouteResult.Status.DIFFERENT_COMPONENTS
                    ? "the roads at the two structures are not connected" : "no road route");
        }
        Route route = result.route();
        double road = start.get().distance() + route.length() + goal.get().distance();
        Set<Integer> edges = new LinkedHashSet<>();
        for (Route.Step step : route.steps()) {
            edges.add(step.edge().id());
        }
        return new Outcome(due, new LaneMeasurement(due.fromStructureId(), due.toStructureId(), true, round(road),
                round(straight), new ArrayList<>(edges), clock.instant(), null), null);
    }

    /** The nearest road point to a structure position (its Location's feet; the network is stored by floor block). */
    private Optional<SnapPoint> snap(RoadNetworkSnapshot snapshot, LanePosition p, RouterParameters routerParameters) {
        return Snapper.snapRanked(snapshot, p.x(), Math.floor(p.y()) - 1, p.z(), maxSnapDistance,
                routerParameters.snapVerticalWeight(), null);
    }

    private RouterParameters parameters() {
        try {
            RouterParameters p = parameters.get();
            return p == null ? RouterParameters.defaults() : p;
        } catch (RuntimeException e) {
            return RouterParameters.defaults();
        }
    }

    private Outcome unreachable(LaneDue due, Double straight, String why) {
        return new Outcome(due, new LaneMeasurement(due.fromStructureId(), due.toStructureId(), false, null,
                straight == null ? null : round(straight), List.of(), clock.instant(), why), null);
    }

    private static Outcome skipped(LaneDue due, String why) {
        return new Outcome(due, null, why);
    }

    private static double round(double blocks) {
        return Math.round(blocks * 100.0) / 100.0;
    }
}

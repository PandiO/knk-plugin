package net.knightsandkings.knk.paper.npc.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.RoadGoals;
import net.knightsandkings.knk.core.roads.route.RoadGoalChoice;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import net.knightsandkings.knk.core.roads.route.Snapper;

/**
 * {@link RoadGoals} on the road network the router reads (link G1, PandiO/knk-workspace#814): today's goal is the road
 * point at the end of the NPC's road route, and the other candidates are the off-road chain's
 * ({@link RoadGoalChoice#candidatesAlongRoad}, F3): the nearest point of each road edge within {@code reach} of the
 * destination ({@code navigation.max-snap-distance}, as for players) and the road's nodes onward from today's goal.
 * Calls {@code RoadGoalChoice} as it is. Main thread or any: it reads an immutable snapshot.
 */
public final class NpcRoadGoals implements RoadGoals {

    /** How far from the road's end today's goal may lie on the network (the route line is the road's polyline). */
    static final double ON_ROAD = 2.0;

    private final Function<String, RoadNetworkSnapshot> networks;
    private final DoubleSupplier reach;

    /**
     * @param networks the road network of a world, as the router has it now (null or empty: none)
     * @param reach    the candidates' reach from the destination ({@code navigation.max-snap-distance})
     */
    public NpcRoadGoals(Function<String, RoadNetworkSnapshot> networks, DoubleSupplier reach) {
        this.networks = Objects.requireNonNull(networks, "networks");
        this.reach = Objects.requireNonNull(reach, "reach");
    }

    @Override
    public List<Candidate> candidates(NavPoint roadEnd, NavPoint goalFloor, int max) {
        if (!roadEnd.world().equals(goalFloor.world())) {
            return List.of();
        }
        RoadNetworkSnapshot network = networks.apply(goalFloor.world());
        if (network == null || network.isEmpty()) {
            return List.of();
        }
        Optional<SnapPoint> today = Snapper.snapRanked(network, roadEnd.x(), roadEnd.y(), roadEnd.z(), ON_ROAD, 1.0,
            null);
        if (today.isEmpty()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        for (SnapPoint p : RoadGoalChoice.candidatesAlongRoad(network, goalFloor.x(), goalFloor.y(), goalFloor.z(),
            reach.getAsDouble(), max, today.get())) {
            out.add(new Candidate(new NavPoint(goalFloor.world(), p.x(), p.y(), p.z()), p));
        }
        return out;
    }
}

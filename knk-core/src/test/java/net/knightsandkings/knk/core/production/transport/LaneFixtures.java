package net.knightsandkings.knk.core.production.transport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/**
 * A small road network for the lane tests. Floor blocks at y 64 (feet at 65).
 * <pre>
 *  z=0     1 ●━━━━━ e10 ━━━━━● 2           island (component 2):  5 ●━ e20 ━● 6   at z=500, x 500..520
 *          (0,0)              ┃ e11
 *  z=100                      ● 3 (100,100)
 * </pre>
 * Logger at feet (0, 65, -10), 10 blocks from node 1; Docks warehouse at feet (100, 65, 110), 10 blocks from node 3:
 * road distance 10 + 200 + 10 = 220, straight sqrt(100² + 120²) = 156.2.
 */
final class LaneFixtures {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC);
    static final int LOGGER = 4;
    static final int DOCKS = 9;
    static final int ISLAND_WAREHOUSE = 12;
    static final LanePosition LOGGER_AT = new LanePosition("world", 0, 65, -10);
    static final LanePosition DOCKS_AT = new LanePosition("world", 100, 65, 110);
    static final LanePosition ISLAND_AT = new LanePosition("world", 510, 65, 505);
    static final LanePosition FAR_AT = new LanePosition("world", 0, 65, -200);

    private LaneFixtures() {
    }

    static RoadNetworkSnapshot network() {
        return RoadNetworkSnapshot.builder("world")
                .addNode(new RoadNode(1, 0, 64, 0, RoadNodeKind.ENDPOINT, null, 1))
                .addNode(new RoadNode(2, 100, 64, 0, RoadNodeKind.JUNCTION, null, 1))
                .addNode(new RoadNode(3, 100, 64, 100, RoadNodeKind.ENDPOINT, null, 1))
                .addNode(new RoadNode(5, 500, 64, 500, RoadNodeKind.ENDPOINT, null, 2))
                .addNode(new RoadNode(6, 520, 64, 500, RoadNodeKind.ENDPOINT, null, 2))
                .addEdge(edge(10, 1, 2, List.of(new int[] {0, 64, 0}, new int[] {100, 64, 0}), 100))
                .addEdge(edge(11, 2, 3, List.of(new int[] {100, 64, 0}, new int[] {100, 64, 100}), 100))
                .addEdge(edge(20, 5, 6, List.of(new int[] {500, 64, 500}, new int[] {520, 64, 500}), 20))
                .build();
    }

    static RoadEdge edge(int id, int from, int to, List<int[]> geometry, double length) {
        return new RoadEdge(id, from, to, geometry, length, 3.0, OptionalInt.empty(), OptionalInt.empty(), 1.0,
                EnumSet.noneOf(RoadEdgeFlag.class), List.of(), List.of(), List.of(), RoadEdgeSource.DETECTED, false);
    }

    static LaneMeasurer measurer() {
        RoadNetworkSnapshot network = network();
        return new LaneMeasurer(world -> "world".equals(world) ? network : null,
                net.knightsandkings.knk.core.roads.route.RouterParameters.defaults(), 48, CLOCK);
    }

    static LaneDue due(int from, int to, LanePosition fromAt, LanePosition toAt) {
        return new LaneDue(from, to, "S" + from, "S" + to, fromAt, toAt, "Missing", null);
    }
}

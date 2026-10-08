package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.route.EdgePolyline;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/** The two parts of a blocked start edge, tagged from the world (live test 2026-10-08, N6). */
class NavigationAccessPartTest {

    private final RoadEdge road = new RoadEdge(10139, 1, 2, List.of(new int[] {0, 42, 0}, new int[] {40, 42, 0}), 40, 3,
        OptionalInt.empty(), OptionalInt.empty(), 1.0, Set.of(), List.of(13), List.of(), List.of("town_1", "gate_2000131"),
        RoadEdgeSource.RECORDED, false);
    private final GateCells southGate = (x, y, z) -> x == 30 && y == 43 ? OptionalInt.of(13) : OptionalInt.empty();

    @Test
    void onlyThePartTowardsTheGateCarriesIt() {
        EdgePolyline polyline = RoadNetworkSnapshot.builder("world")
            .addNode(new RoadNode(1, 0, 42, 0, RoadNodeKind.ENDPOINT, null, 1, false))
            .addNode(new RoadNode(2, 40, 42, 0, RoadNodeKind.ENDPOINT, null, 1, false))
            .addEdge(road).build().polyline(road);
        RoadEdge back = NavigationAccess.partOf(road, polyline.subPolyline(20, 0), b -> b[0] >= 25 ? Set.of("gate_2000131") : Set.of("town_1"),
            southGate).orElseThrow();
        RoadEdge ahead = NavigationAccess.partOf(road, polyline.subPolyline(20, 40), b -> b[0] >= 25 ? Set.of("gate_2000131") : Set.of("town_1"),
            southGate).orElseThrow();

        assertEquals(List.of(), back.gateDoorIds(), "the stored tags of the whole edge are not copied");
        assertEquals(List.of("town_1"), back.regionIds());
        assertEquals(List.of(13), ahead.gateDoorIds());
        assertTrue(ahead.regionIds().contains("gate_2000131"));
        assertTrue(NavigationAccess.partOf(road, polyline.subPolyline(0, 0), b -> Set.of(), southGate).isEmpty(),
            "standing on the node: nothing to walk");
    }
}

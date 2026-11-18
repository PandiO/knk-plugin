package net.knightsandkings.knk.core.landmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.navigation.model.Landmark;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/** Off-road phase 5.2 (PandiO/knk-workspace#709, DESIGN § 5.2.1, § 6.2.2): the road nodes landmarks stand on. */
class LandmarkLinkNodesTest {

    static RoadNetworkSnapshot road() {
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder("world");
        b.addProfile(new RoadNetworkSnapshot.Profile(1, "Main", RoadClass.MAIN, 1.0));
        b.addNode(new RoadNode(1, 0, 64, 0, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(new RoadNode(2, 20, 64, 0, RoadNodeKind.BOUNDARY, null, 1));
        b.addNode(new RoadNode(3, 40, 64, 0, RoadNodeKind.SPLIT, null, 1));
        b.addNode(new RoadNode(4, 60, 64, 0, RoadNodeKind.PRUNED, null, 1));
        b.addEdge(edge(10, 1, 2, 0, 20));
        b.addEdge(edge(11, 2, 3, 20, 40));
        return b.build();
    }

    private static RoadEdge edge(int id, int from, int to, int x0, int x1) {
        return new RoadEdge(id, from, to, List.of(new int[] {x0, 64, 0}, new int[] {x1, 64, 0}), x1 - x0, 3,
            OptionalInt.of(1), OptionalInt.empty(), 1.0, EnumSet.noneOf(RoadEdgeFlag.class), List.of(), List.of(),
            List.of(), RoadEdgeSource.DETECTED, false);
    }

    private static Landmark at(int id, String world, double x, double y, double z) {
        return new Landmark(id, "L" + id, world, x, y, z, Landmark.Extent.NONE, null, null, null, null, true);
    }

    @Test
    void aLandmarkOnTheRoadMakesItsNearestNodeALandmarkNode() {
        Map<Integer, Integer> nodes = LandmarkLinkNodes.of(road(), List.of(
            at(7, "world", 1.0, 65, 1.0),    // on node 1 (feet at floor + 1)
            at(8, "WORLD", 22.0, 66, 2.0),   // 2 across, 1 up from node 2
            at(9, "world", 10.0, 65, 0.0)));  // between 1 and 2, 9.5 from either: not on the road

        assertEquals(Map.of(1, 7, 2, 8), nodes);
    }

    @Test
    void farLandmarksSplitsTombstonesAndOtherWorldsTakeNoPart() {
        RoadNetworkSnapshot road = road();
        assertTrue(LandmarkLinkNodes.of(road, List.of(at(1, "world", 40.5, 65, 0.5))).isEmpty(), "a split node");
        assertTrue(LandmarkLinkNodes.of(road, List.of(at(1, "world", 60.5, 65, 0.5))).isEmpty(), "a pruned node");
        assertTrue(LandmarkLinkNodes.of(road, List.of(at(1, "world", 0.5, 69, 0.5))).isEmpty(), "4 above");
        assertTrue(LandmarkLinkNodes.of(road, List.of(at(1, "nether", 0.5, 65, 0.5))).isEmpty());
        assertTrue(LandmarkLinkNodes.of(road, List.of()).isEmpty());
        assertTrue(LandmarkLinkNodes.of(road, null).isEmpty());
    }

    @Test
    void twoLandmarksOnOneNodeKeepTheLowerId() {
        assertEquals(Map.of(1, 3), LandmarkLinkNodes.of(road(), List.of(at(5, "world", 0.5, 65, 0.5),
            at(3, "world", 1.5, 65, 0.5))));
    }
}

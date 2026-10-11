package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.knightsandkings.knk.core.roads.route.NetworkFixture.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadNetworkSnapshotTest {

    private final RoadNetworkSnapshot town = NetworkFixture.town();

    @Test
    void nodesAndEdgesAreLookedUpById() {
        assertEquals(13, town.nodeCount());
        assertEquals(14, town.edgeCount());
        assertEquals("Kardenna Castle", town.requireNode(CASTLE).name());
        assertEquals(C, town.requireEdge(E_C_CASTLE).fromNodeId());
        assertTrue(town.node(999).isEmpty());
        assertTrue(town.edge(999).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> town.requireNode(999));
        assertEquals(List.of("Cinix Keep gate", "Kardenna Castle", "Kardenna Mill"),
            town.namedNodes().stream().map(RoadNode::name).toList());
    }

    @Test
    void incidentEdgesCoverBothEnds() {
        Set<Integer> atC = new HashSet<>();
        for (RoadEdge e : town.edgesAt(C)) {
            atC.add(e.id());
        }
        assertEquals(Set.of(E_BC, E_CD, E_AC, E_C_CASTLE, E_TUNNEL), atC);
        assertEquals(0, town.incidentEdgeIndexes(999).length);
    }

    @Test
    void polylinesAreDecodedOnce() {
        EdgePolyline p = town.polyline(E_TUNNEL);
        assertEquals(4, p.pointCount());
        assertEquals(3, p.segmentCount());
        assertEquals(20.88, p.segmentLength(0), 0.01);
        assertEquals(60, p.segmentLength(1), 1e-9);
        assertEquals(101.76, p.length(), 0.01);
        assertTrue(town.requireEdge(E_TUNNEL).length() >= p.length(), "walked length >= polyline length");
        double[] mid = p.pointAt(p.length() / 2);
        assertEquals(150, mid[0], 1e-9);
        assertEquals(58, mid[1], 1e-9);
        assertEquals(1, p.segmentAt(30));
        assertEquals(2, p.segmentAt(p.length()));
        assertEquals(0, p.segmentAt(-5));
        assertEquals(p.cumulativeAt(1) + 30, p.along(1, 0.5), 1e-9);
    }

    @Test
    void stitchEdgeToAMissingNeighbourNodeIsDroppedAndReported() {
        RoadNetworkSnapshot.Builder b = NetworkFixture.townBuilder();
        // pretend the neighbour tile (node 23, edge 27) was not downloaded
        RoadNetworkSnapshot partial = RoadNetworkSnapshot.builder("world")
            .addProfile(new RoadNetworkSnapshot.Profile(PROFILE_MAIN, "m", RoadClass.MAIN, 1.0))
            .addNode(town.requireNode(E)).addNode(town.requireNode(BOUNDARY_W))
            .addEdge(town.requireEdge(E_E_BOUNDARY)).addEdge(town.requireEdge(E_STITCH)).build();
        assertEquals(1, partial.edgeCount());
        assertEquals(List.of(E_STITCH), partial.unresolvedEdgeIds());
        assertEquals(List.of(), town.unresolvedEdgeIds());
        assertEquals(14, b.build().edgeCount());
    }

    @Test
    void regionIdsProfilesAndStreetsAreExposed() {
        assertEquals(Set.of(CASTLE_REGION), town.regionIds());
        assertEquals(RoadClass.PATH, town.roadClass(town.requireEdge(E_AD)).orElseThrow());
        assertTrue(town.roadClass(town.requireEdge(E_STITCH)).isEmpty());
        assertEquals("Merchantstreet", town.streetOf(town.requireEdge(E_BC)).orElseThrow());
        assertTrue(town.streetOf(town.requireEdge(E_AD)).isEmpty());
        assertEquals(1, town.componentOf(town.requireEdge(E_AB)));
        assertEquals(2, town.componentOf(town.requireEdge(E_BRIDGE)));
    }

    @Test
    void costFactorMultipliesClassProfileAndEdgeMultipliers() {
        Map<RoadClass, Double> classCost = RouterParameters.defaults().classCost();
        assertEquals(0.9 * 100, town.edgeCost(town.requireEdge(E_AB), classCost), 1e-9);
        assertEquals(1.15 * 100, town.edgeCost(town.requireEdge(E_AD), classCost), 1e-9);
        assertEquals(1.0, town.costFactor(town.requireEdge(E_STITCH), classCost), 1e-9, "no profile → 1.0");
        RoadNetworkSnapshot tuned = RoadNetworkSnapshot.builder("w")
            .addProfile(new RoadNetworkSnapshot.Profile(1, "m", RoadClass.MAIN, 2.0))
            .addNode(node(1, 0, 64, 0, RoadNodeKind.ENDPOINT, null, 1)).addNode(node(2, 10, 64, 0, RoadNodeKind.ENDPOINT, null, 1))
            .addEdge(edge(50, 1, 2, line(0, 64, 0, 10, 64, 0)).profile(1).cost(0.5).build()).build();
        assertEquals(0.9 * 2.0 * 0.5, tuned.costFactor(tuned.requireEdge(50), classCost), 1e-9);
        assertEquals(0.9, tuned.minCostFactor(classCost), 1e-9);
        assertEquals(0.9, town.minCostFactor(classCost), 1e-9);
        assertEquals(1.0, RoadNetworkSnapshot.empty("w").minCostFactor(classCost), 1e-9);
    }

    @Test
    void segmentIndexFindsSegmentsNearAPoint() {
        SegmentIndex index = town.segmentIndex();
        assertEquals(32, index.bucketSize());
        assertEquals(17, index.segmentCount());
        Set<Integer> found = new HashSet<>();
        index.forEachNear(50, 0, 4, (e, s) -> found.add(town.edgeAt(e).id()));
        assertTrue(found.contains(E_AB));
        assertTrue(found.contains(E_BRIDGE), "height is not bucketed: the bridge shares the bucket");
        assertFalse(found.contains(E_CD));
        Set<Integer> far = new HashSet<>();
        index.forEachNear(50, 400, 4, (e, s) -> far.add(e));
        assertTrue(far.isEmpty());
        // negative coordinates bucket correctly (floorDiv)
        Set<Integer> north = new HashSet<>();
        index.forEachNear(50, -20, 2, (e, s) -> north.add(town.edgeAt(e).id()));
        assertEquals(Set.of(E_BRIDGE), north);
    }

    @Test
    void edgeRecordValidatesAndCopies() {
        int[][] geometry = {{0, 64, 0}, {5, 64, 0}};
        RoadEdge e = edge(1, 1, 2, List.of(geometry[0], geometry[1])).flags(RoadEdgeFlag.ONEWAY).build();
        geometry[0][0] = 99;
        assertEquals(0, e.geometry().get(0)[0], "geometry is copied");
        assertTrue(e.isOneway());
        assertTrue(e.allowsTravelFrom(1));
        assertFalse(e.allowsTravelFrom(2));
        assertEquals(2, e.otherNode(1));
        assertEquals(-1, e.otherNode(7));
        assertFalse(e.isStitch());
        assertTrue(edge(2, 1, 2, List.of(geometry[0], geometry[1])).source(RoadEdgeSource.STITCH).build().isStitch());
        assertThrows(IllegalArgumentException.class, () -> edge(3, 1, 2, List.of(new int[] {0, 64, 0})).build());
        assertThrows(IllegalArgumentException.class, () -> node(-1, 0, 0, 0, RoadNodeKind.ENDPOINT, null, 1));
        assertEquals(RoadEdgeFlag.NO_GPS, RoadEdgeFlag.fromApiName("NoGps"));
        assertEquals("NoGps", RoadEdgeFlag.NO_GPS.apiName());
        assertEquals(RoadClass.MAIN, RoadClass.fromApiName("Main"));
        assertEquals("Path", RoadClass.PATH.apiName());
        assertEquals(RoadEdgeSource.STITCH, RoadEdgeSource.fromApiName("Stitch"));
        assertEquals("Recorded", RoadEdgeSource.RECORDED.apiName());
    }
}

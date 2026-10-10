package net.knightsandkings.knk.core.roads.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/** World tags along an edge (live test 2026-10-08, findings N3 and N4). */
class EdgeTaggingTest {

    private static RoadEdge edge(int id, List<int[]> geometry, List<Integer> doors, List<String> regions) {
        return new RoadEdge(id, 1, 2, geometry, 20, 3, OptionalInt.empty(), OptionalInt.empty(), 1.0, Set.of(),
            doors, List.of(), regions, RoadEdgeSource.RECORDED, false);
    }

    @Test
    void samplesFollowThePolylineAtMostAStepApart() {
        List<int[]> samples = EdgeTagging.samples(List.of(new int[] {0, 64, 0}, new int[] {6, 64, 0}, new int[] {6, 66, 4}), 2.0);

        assertArrayEquals(new int[] {0, 64, 0}, samples.get(0));
        assertArrayEquals(new int[] {6, 66, 4}, samples.get(samples.size() - 1));
        for (int i = 1; i < samples.size(); i++) {
            int[] a = samples.get(i - 1);
            int[] b = samples.get(i);
            assertTrue(Math.abs(a[0] - b[0]) <= 2 && Math.abs(a[2] - b[2]) <= 2, "gap at " + i);
        }
        assertEquals(6, EdgeTagging.samples(List.of(new int[] {0, 64, 0}, new int[] {5, 64, 0}), 0.5).size(),
            "every block once, duplicates removed");
    }

    @Test
    void aDoorIsFoundBetweenTwoFarApartPointsAndOnADiagonal() {
        // N3: the South Gate road was recorded through a diagonal door; the recording kept no gate.
        GateCells gates = (x, y, z) -> x == 7 && z == 7 && (y == 65 || y == 66) ? OptionalInt.of(13) : OptionalInt.empty();

        assertEquals(List.of(13), EdgeTagging.doorsAlong(List.of(new int[] {0, 64, 0}, new int[] {14, 64, 14}), gates));
        assertEquals(List.of(), EdgeTagging.doorsAlong(List.of(new int[] {0, 64, 2}, new int[] {14, 64, 2}), gates));
        GateCells two = (x, y, z) -> y == 65 && x == 3 ? OptionalInt.of(4) : y == 65 && x == 9 ? OptionalInt.of(2) : OptionalInt.empty();
        assertEquals(List.of(4, 2), EdgeTagging.doorsAlong(List.of(new int[] {0, 64, 0}, new int[] {12, 64, 0}), two),
            "in the order the edge meets them");
    }

    @Test
    void extraTagsComeAfterTheStoredOnesAndNothingNewKeepsTheEdge() {
        RoadEdge stored = edge(5, List.of(new int[] {0, 64, 0}, new int[] {10, 64, 0}), List.of(), List.of("town_1", "gate_2"));

        assertSame(stored, EdgeTagging.withExtraTags(stored, List.of("gate_2"), List.of()));
        RoadEdge tagged = EdgeTagging.withExtraTags(stored, List.of("domain_16", "town_1"), List.of(13));
        assertEquals(List.of("town_1", "gate_2", "domain_16"), tagged.regionIds());
        assertEquals(List.of(13), tagged.gateDoorIds());
        assertEquals(stored.source(), tagged.source());
        assertEquals(stored.length(), tagged.length());
    }

    @Test
    void aSnapshotCanBeRetaggedKeepingItsNodesAndProfiles() {
        RoadNetworkSnapshot snapshot = RoadNetworkSnapshot.builder("world")
            .addNode(new RoadNode(1, 0, 64, 0, RoadNodeKind.JUNCTION, null, 1, false))
            .addNode(new RoadNode(2, 10, 64, 0, RoadNodeKind.JUNCTION, null, 1, false))
            .addEdge(edge(5, List.of(new int[] {0, 64, 0}, new int[] {10, 64, 0}), List.of(), List.of()))
            .addStreet(new RoadNetworkSnapshot.Street(3, "Main Street"))
            .build();

        RoadNetworkSnapshot retagged = snapshot.retag(e -> EdgeTagging.withExtraTags(e, List.of("domain_16"), List.of(13)));

        assertEquals(List.of("domain_16"), retagged.requireEdge(5).regionIds());
        assertEquals(List.of(13), retagged.requireEdge(5).gateDoorIds());
        assertEquals(Set.of("domain_16"), retagged.regionIds());
        assertEquals(2, retagged.nodeCount());
        assertEquals("Main Street", retagged.streetName(3).orElseThrow());
        assertEquals(List.of(), snapshot.requireEdge(5).regionIds(), "the original is unchanged");
    }
}

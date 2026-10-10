package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static net.knightsandkings.knk.core.roads.route.NetworkFixture.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapperTest {

    private final RoadNetworkSnapshot town = NetworkFixture.town();
    private final Snapper snapper = new Snapper(town, RouterParameters.defaults());

    @Test
    void playerOnTheBridgeSnapsToTheBridgeNotTheRoadBelow() {
        // feet at y 73 = standing on the bridge floor (72); the road A–B floor is 64, eight blocks down
        SnapPoint s = snapper.snap(50, 73, 0).orElseThrow();
        assertEquals(E_BRIDGE, s.edgeId());
        assertEquals(0, s.distance(), 1e-9);
        assertEquals(30, s.along(), 1e-9);
        assertEquals(72, s.y(), 1e-9);
    }

    @Test
    void playerOnTheRoadUnderTheBridgeSnapsToTheRoad() {
        SnapPoint s = snapper.snap(50, 65, 0).orElseThrow();
        assertEquals(E_AB, s.edgeId());
        assertEquals(0, s.distance(), 1e-9);
        assertEquals(50, s.along(), 1e-9);
        assertEquals(0, s.segmentIndex());
        assertEquals(0.5, s.t(), 1e-9);
    }

    @Test
    void verticalWeightDecidesBetweenLevels() {
        // feet at y 69: floor 68, 4 above the road and 4 below the bridge → tie on height; x offset breaks it
        Snapper unweighted = new Snapper(town, RouterParameters.defaults().withSnap(48, 1));
        assertEquals(E_AB, unweighted.snap(50, 69, 0).orElseThrow().edgeId(), "equal distance → lower edge index");
        assertEquals(E_BRIDGE, unweighted.snap(50, 69.5, 0).orElseThrow().edgeId(), "half a block up → bridge");
        // with weight 4 a player 1 block above the road (floor 65) is 4 away from the road, 28 from the bridge
        SnapPoint s = snapper.snap(50, 66, 0).orElseThrow();
        assertEquals(E_AB, s.edgeId());
        assertEquals(4, s.distance(), 1e-9);
    }

    @Test
    void theRankedSnapReachesARoadBelowInPlain3d() {
        // KNG-75: 30 blocks above the boundary road (floor 94) is 120 weighted - out of reach - but 30 plain
        assertTrue(snapper.snap(250, 95, 0).isEmpty());
        SnapPoint s = Snapper.snapRanked(town, 250, 94, 0, 48, 4, null).orElseThrow();
        assertEquals(E_E_BOUNDARY, s.edgeId());
        assertEquals(30, s.distance(), 1e-9, "the plain distance");
        assertTrue(Snapper.snapRanked(town, 250, 94, 40, 48, 4, null).isEmpty(), "plain 50 > 48");
    }

    @Test
    void theRankedSnapStillLetsTheHeightWeightPickTheBridge() {
        // beside the bridge at its height (floor 72): 10 blocks from it, 8 above road A–B - plain 3D would pick the road
        SnapPoint s = Snapper.snapRanked(town, 60, 72, 0, 48, 4, null).orElseThrow();
        assertEquals(E_BRIDGE, s.edgeId());
        assertEquals(10, s.distance(), 1e-9);
        assertEquals(E_AB, Snapper.snapRanked(town, 60, 72, 0, 48, 1, null).orElseThrow().edgeId(), "unweighted: the road");
    }

    @Test
    void tooFarFromAnyRoadIsEmpty() {
        assertTrue(snapper.snap(50, 65, 400).isEmpty());
        assertTrue(new Snapper(RoadNetworkSnapshot.empty("w"), RouterParameters.defaults()).snap(0, 65, 0).isEmpty());
        // 47 blocks off the road is still fine, 49 is not (x 250: only edge 25 is anywhere near)
        assertEquals(E_E_BOUNDARY, snapper.snap(250, 65, 47).orElseThrow().edgeId());
        assertTrue(snapper.snap(250, 65, 49).isEmpty());
    }

    @Test
    void snapsToTheInteriorOfAPolylineSegment() {
        // the tunnel edge dips: (120,58) → (180,58); a player in the tunnel at x 150 with feet y 59
        SnapPoint s = snapper.snap(150, 59, 100).orElseThrow();
        assertEquals(E_TUNNEL, s.edgeId());
        assertEquals(1, s.segmentIndex());
        assertEquals(0.5, s.t(), 1e-9);
        assertEquals(58, s.y(), 1e-9);
        assertEquals(town.polyline(E_TUNNEL).cumulativeAt(1) + 30, s.along(), 1e-9);
    }

    @Test
    void beyondAnEdgeEndClampsToTheNode() {
        // 3 blocks north of A along nothing: closest is A itself (t = 0 on edge 10 or 13; lower index wins)
        SnapPoint s = snapper.snap(0, 65, -3).orElseThrow();
        assertEquals(0, s.along(), 1e-9);
        assertEquals(3, s.distance(), 1e-9);
        assertEquals(0, s.x(), 1e-9);
        assertEquals(0, s.z(), 1e-9);
    }

    @Test
    void staticSnapTakesFloorCoordinates() {
        Optional<SnapPoint> s = Snapper.snap(town, 50, 64, 0, 48, 4);
        assertEquals(E_AB, s.orElseThrow().edgeId());
        assertEquals(0, s.orElseThrow().distance(), 1e-9);
    }

    @Test
    void helpersBuildSnapPointsOnTheNetwork() {
        SnapPoint atC = SnapPoint.atNode(town, C);
        assertEquals(100, atC.x(), 1e-9);
        assertEquals(100, atC.z(), 1e-9);
        SnapPoint mid = SnapPoint.onEdge(town, E_AB, 25);
        assertEquals(25, mid.x(), 1e-9);
        assertEquals(25, mid.along(), 1e-9);
        assertEquals(100, SnapPoint.onEdge(town, E_AB, 500).along(), 1e-9, "clamped to the edge");
        assertThrows(IllegalArgumentException.class, () -> SnapPoint.atNode(town, 999));
    }

    @Test
    void parametersValidate() {
        RouterParameters p = RouterParameters.defaults();
        assertEquals(48, p.maxSnapDistance());
        assertEquals(4, p.snapVerticalWeight());
        assertEquals(0.9, p.classCost(RoadClass.MAIN));
        assertEquals(1.15, p.classCost(RoadClass.PATH));
        assertEquals(1.0, p.withClassCost(Map.of()).classCost(RoadClass.MAIN), "missing class → 1.0");
        assertThrows(IllegalArgumentException.class, () -> p.withSnap(0, 4));
        assertThrows(IllegalArgumentException.class, () -> p.withClassCost(Map.of(RoadClass.MAIN, 0.0)));
    }
}

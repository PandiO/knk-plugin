package net.knightsandkings.knk.core.production.transport;

import static net.knightsandkings.knk.core.production.transport.LaneFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.RouterParameters;

/** The lane measurer (PandiO/knk-workspace#681, DESIGN § 7.6, A12, A13) over a built road network. */
class LaneMeasurerTest {

    @Test
    void aReachableLane_isTheWalkToTheRoad_theRoadRoute_andTheWalkFromIt_withItsEdgesInOrder() {
        LaneMeasurer.Outcome outcome = measurer().measure(due(LOGGER, DOCKS, LOGGER_AT, DOCKS_AT));

        assertTrue(outcome.measured());
        LaneMeasurement lane = outcome.measurement();
        assertTrue(lane.reachable());
        assertEquals(220.0, lane.roadDistance(), 0.01, "10 to the road, 200 along it, 10 from it (A12)");
        assertEquals(156.2, lane.straightDistance(), 0.01);
        assertEquals(List.of(10, 11), lane.routeEdgeIds());
        assertEquals(LOGGER, lane.fromStructureId());
        assertEquals(DOCKS, lane.toStructureId());
        assertEquals(CLOCK.instant(), lane.measuredAt());
    }

    @Test
    void theSameLaneMeasuredBackwards_hasTheSameLength() {
        LaneMeasurement back = measurer().measure(due(DOCKS, LOGGER, DOCKS_AT, LOGGER_AT)).measurement();
        assertEquals(220.0, back.roadDistance(), 0.01);
        assertEquals(List.of(11, 10), back.routeEdgeIds());
    }

    @Test
    void aStructureFarFromAnyRoad_isUnreachable_withTheReason_A13() {
        LaneMeasurement lane = measurer().measure(due(LOGGER, DOCKS, FAR_AT, DOCKS_AT)).measurement();

        assertFalse(lane.reachable());
        assertNull(lane.roadDistance(), "an unreachable lane has no road distance");
        assertTrue(lane.why().contains("#4 is more than 48 blocks from a road"), lane.why());
        assertEquals(List.of(), lane.routeEdgeIds());
        assertEquals(LOGGER_AT.distanceTo(DOCKS_AT) > 0, true);
    }

    @Test
    void roadsThatDoNotJoin_areUnreachable() {
        LaneMeasurement lane = measurer().measure(due(LOGGER, ISLAND_WAREHOUSE, LOGGER_AT, ISLAND_AT)).measurement();

        assertFalse(lane.reachable());
        assertTrue(lane.why().contains("not connected"), lane.why());
        assertTrue(lane.straightDistance() > 0);
    }

    @Test
    void differentWorlds_areUnreachable_withoutAStraightDistance() {
        LaneMeasurement lane = measurer().measure(due(LOGGER, DOCKS, LOGGER_AT,
                new LanePosition("world_nether", 100, 65, 110))).measurement();

        assertFalse(lane.reachable());
        assertNull(lane.straightDistance());
    }

    @Test
    void noPosition_orNoRoadNetworkLoaded_isSkipped_neverReportedAsNoRoad() {
        LaneMeasurer measurer = measurer();
        LaneMeasurer.Outcome noPosition = measurer.measure(due(LOGGER, DOCKS, null, DOCKS_AT));
        assertFalse(noPosition.measured());
        assertTrue(noPosition.skipped().contains("#4 has no location"), noPosition.skipped());

        LaneMeasurer.Outcome noNetwork = measurer.measure(due(LOGGER, DOCKS, new LanePosition("other", 0, 65, 0),
                new LanePosition("other", 10, 65, 0)));
        assertFalse(noNetwork.measured());
        assertTrue(noNetwork.skipped().contains("no road network loaded"), noNetwork.skipped());

        LaneMeasurer emptyNetwork = new LaneMeasurer(w -> RoadNetworkSnapshot.empty(w), RouterParameters.defaults(), 48,
                CLOCK);
        assertFalse(emptyNetwork.measure(due(LOGGER, DOCKS, LOGGER_AT, DOCKS_AT)).measured());
    }

    @Test
    void theSnapDistanceIsTheSettings() {
        RoadNetworkSnapshot network = network();
        LaneMeasurer tight = new LaneMeasurer(w -> network, RouterParameters.defaults(), 5, CLOCK);
        assertFalse(tight.measure(due(LOGGER, DOCKS, LOGGER_AT, DOCKS_AT)).measurement().reachable(),
                "10 blocks from the road is too far with max-snap-distance 5");
    }
}

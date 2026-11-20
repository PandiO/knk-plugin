package net.knightsandkings.knk.core.production.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Road connections from measured lanes (PandiO/knk-workspace#681, D5). */
class RoadConnectionBookTest {

    private static LaneMeasurement lane(int from, int to, boolean reachable) {
        return new LaneMeasurement(from, to, reachable, reachable ? 100.0 : null, 80.0, List.of(), null, null);
    }

    @Test
    void oneReachableLaneConnects_everyLaneUnreachableDisconnects_noLaneIsNotJudged() {
        RoadConnectionBook book = new RoadConnectionBook();
        book.record(lane(1, 100, true));
        book.record(lane(2, 100, false));
        book.record(lane(2, 101, false));

        assertEquals(Map.of(1, RoadConnection.Connected, 2, RoadConnection.Unconnected, 100, RoadConnection.Connected,
                101, RoadConnection.Unconnected), book.judge(List.of(1, 2, 3, 100, 101)));
    }

    @Test
    void anEarlierReachableLane_keepsTheStructureConnected_whenAnotherLaneHasNoRoad() {
        RoadConnectionBook book = new RoadConnectionBook();
        book.record(lane(2, 100, true));
        book.record(lane(2, 101, false));
        assertEquals(RoadConnection.Connected, book.judge(List.of(2)).get(2));

        book.record(lane(100, 2, false)); // the same lane measured the other way: it replaces the earlier result
        assertEquals(RoadConnection.Unconnected, book.judge(List.of(2)).get(2));
        assertEquals(2, book.lanesKnown());
    }

    @Test
    void onlyChangesAreSent_andA404StructureIsNeverSentAgain() {
        RoadConnectionBook book = new RoadConnectionBook();
        assertTrue(book.shouldSend(1, RoadConnection.Connected));
        book.sent(1, RoadConnection.Connected);
        assertFalse(book.shouldSend(1, RoadConnection.Connected));
        assertTrue(book.shouldSend(1, RoadConnection.Unconnected));

        book.notAProducer(100);
        assertFalse(book.shouldSend(100, RoadConnection.Connected));
    }
}

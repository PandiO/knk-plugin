package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadEdgeRecord;
import net.knightsandkings.knk.core.roads.build.GateCells;

/** A recorded stretch carries the gate doors it was walked through (live test 2026-10-08, finding N3). */
class RoadSurveyRecordTest {

    @Test
    void aRecordedStretchIsTaggedWithTheGateItPassed() {
        // walked through a diagonal door (the South Gate faces south-east); simplified to its two ends
        GateCells southGate = (x, y, z) -> x == 4 && z == 4 && y == 43 ? OptionalInt.of(13) : OptionalInt.empty();
        List<int[]> walked = List.of(new int[] {0, 42, 0}, new int[] {2, 42, 2}, new int[] {5, 42, 5}, new int[] {8, 42, 8});
        List<int[]> geometry = List.of(new int[] {0, 42, 0}, new int[] {8, 42, 8});

        RoadEdgeRecord record = RoadSurveyService.recordOf("world", walked, geometry, List.of("gate_2000131"),
            OptionalInt.of(3), southGate);

        assertEquals(List.of(13), record.gateDoorIds());
        assertEquals(List.of("gate_2000131"), record.regionIds());
        assertEquals(2, record.geometry().size(), "the simplified geometry is uploaded");
        assertEquals(List.of(), RoadSurveyService.recordOf("world", walked, geometry, List.of(), OptionalInt.empty(),
            GateCells.NONE).gateDoorIds());
    }
}

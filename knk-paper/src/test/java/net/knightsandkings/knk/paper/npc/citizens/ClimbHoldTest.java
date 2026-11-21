package net.knightsandkings.knk.paper.npc.citizens;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Link H1 (PandiO/knk-workspace#779): a climbing body is drawn back to its ladder column's waypoint. The case is the
 * {@code npc-ladder-hatch} run that stuck: the body had crept to x 23.28 under a waypoint at 23.50 and caught its head
 * on the roof block beside the hatch.
 */
class ClimbHoldTest {

    @Test
    void aBodyThatCreptSidewaysOnTheLadderIsDrawnBack() {
        double[] pull = ClimbHold.pull(true, 23.28, 21.49, 23.5, 21.5);
        assertArrayEquals(new double[] {0.1, 0.005}, pull, 1e-9, "toward the middle, at most 0.1 a tick");
        assertArrayEquals(new double[] {-0.04, 0.0}, ClimbHold.pull(true, 23.58, 21.5, 23.5, 21.5), 1e-9);
    }

    @Test
    void closeToTheMiddleNotClimbingOrAnotherColumnIsLeftAlone() {
        assertNull(ClimbHold.pull(true, 23.47, 21.49, 23.5, 21.5), "close enough");
        assertNull(ClimbHold.pull(false, 23.28, 21.49, 23.5, 21.5), "not on a ladder");
        assertNull(ClimbHold.pull(true, 23.28, 21.49, 23.5, 22.5), "stepping off the top onto the roof beside");
        assertNull(ClimbHold.pull(true, 22.9, 21.49, 23.5, 21.5), "already in the next column: the engine steers");
    }

    /**
     * PandiO/knk-workspace#956, the developer's tower: from the roof (feet 107.88 in the hatch) the walk goes straight
     * down the ladder at 1400 -540 (feet 106 to 99), then out of the shaft at the foot.
     */
    private static final List<double[]> DOWN = List.of(
        new double[] {1400.5, 106, -539.5}, new double[] {1400.5, 105, -539.5}, new double[] {1400.5, 104, -539.5},
        new double[] {1400.5, 103, -539.5}, new double[] {1400.5, 99, -539.5}, new double[] {1399.5, 99, -540.5},
        new double[] {1398.5, 99, -540.5});

    @Test
    void aWaypointStraightBelowAClimbingBodyIsARunDownItsColumn() {
        assertEquals(4, ClimbHold.descentEnd(true, 1400.49, 107.88, -539.51, DOWN, 0),
            "down to the foot at 99, the waypoint before the walk out of the shaft");
        assertEquals(4, ClimbHold.descentEnd(true, 1400.5, 105.2, -539.5, DOWN, 2), "from part way down too");
    }

    @Test
    void noRunDownWithoutAClimbOrAWaypointFarEnoughBelowInItsColumn() {
        assertEquals(-1, ClimbHold.descentEnd(false, 1400.49, 107.88, -539.51, DOWN, 0), "not on a ladder");
        assertEquals(-1, ClimbHold.descentEnd(true, 1400.5, 106.5, -539.5, DOWN, 0),
            "half a block above it: Citizens counts it reached");
        assertEquals(-1, ClimbHold.descentEnd(true, 1400.5, 99.0, -539.5, DOWN, 5), "the walk out of the shaft");
        assertEquals(-1, ClimbHold.descentEnd(true, 1401.2, 107.88, -539.5, DOWN, 0), "beside the hatch, not in it");
        assertEquals(-1, ClimbHold.descentEnd(true, 1400.5, 107.88, -539.5, DOWN, -1), "not on the list");
        List<double[]> up = List.of(new double[] {1400.5, 100, -539.5}, new double[] {1400.5, 106, -539.5});
        assertEquals(0, ClimbHold.descentEnd(true, 1400.5, 101.2, -539.5, up, 0),
            "one step down, then the run ends where the list goes up again");
    }

    @Test
    void theFeetLandHalfABlockAboveTheRunsLastWaypoint() {
        double[] foot = DOWN.get(4);
        assertFalse(ClimbHold.landed(100.0, foot));
        assertTrue(ClimbHold.landed(99.45, foot));
        assertTrue(ClimbHold.landed(99.0, foot));
    }
}

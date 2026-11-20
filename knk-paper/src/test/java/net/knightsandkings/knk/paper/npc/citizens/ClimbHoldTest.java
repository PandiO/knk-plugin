package net.knightsandkings.knk.paper.npc.citizens;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}

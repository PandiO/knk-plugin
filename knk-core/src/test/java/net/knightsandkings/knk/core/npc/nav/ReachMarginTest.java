package net.knightsandkings.knk.core.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;

/**
 * Link J1 (PandiO/knk-workspace#695): on a list that climbs, a step counts as reached only once the body stands on it,
 * so the engine never steers to a waypoint a block above the feet from beside the step.
 */
class ReachMarginTest {

    private static NavPoint p(double x, double y, double z) {
        return new NavPoint("world", x, y, z);
    }

    /**
     * The replay's hall entry (link J1's diagnostic, scene coordinates): a follower at feet 115.0 beside the stair, the
     * step's waypoint half a block up 0.66 across, the next a block up 0.99 across; the list's margin was 1.0.
     */
    private static final NavPoint FEET = p(30020.45, 115.0, 30067.64);
    private static final List<NavPoint> HALL_ENTRY = List.of(p(30020.68, 115.0, 30066.96), p(30020.77, 115.0, 30068.20),
        p(30019.80, 115.5, 30067.75), p(30019.77, 116.0, 30066.92));

    @Test
    void theHallEntryListGetsTheClimbMargin() {
        double margin = ReachMargin.of(FEET, HALL_ENTRY, 1.0);

        assertEquals(ReachMargin.CLIMB, margin, 1e-9);
        NavPoint step = HALL_ENTRY.get(2);
        assertTrue(FEET.horizontalDistance(step) > margin,
            "the step is no longer reached from beside it (it was within 1.0)");
        assertTrue(FEET.horizontalDistance(step) <= 1.0, "with the old margin it was");
    }

    @Test
    void theClimbMarginIsUnderHalfABodysWidth() {
        assertTrue(ReachMargin.CLIMB < ReachMargin.BODY_HALF_WIDTH);
    }

    @Test
    void aFlatListKeepsItsMargin() {
        List<NavPoint> flat = List.of(p(0.5, 65, 0.5), p(3.5, 65, 0.5), p(3.5, 64.9375, 4.5), p(6.5, 65, 4.5));

        assertFalse(ReachMargin.climbs(p(0.5, 65, -1), flat), "a path block's sixteenth is no climb");
        assertEquals(1.0, ReachMargin.of(p(0.5, 65, -1), flat, 1.0), 1e-9);
    }

    @Test
    void aListGoingDownKeepsItsMargin() {
        List<NavPoint> down = List.of(p(0.5, 66, 0.5), p(1.5, 65.5, 0.5), p(2.5, 65, 0.5));

        assertEquals(0.5, ReachMargin.of(p(0.5, 66, -0.5), down, 0.5), 1e-9);
    }

    @Test
    void aFirstWaypointAboveTheFeetIsAClimb() {
        List<NavPoint> one = List.of(p(1.5, 65.5, 0.5));

        assertTrue(ReachMargin.climbs(p(0.5, 65, 0.5), one));
        assertFalse(ReachMargin.climbs(null, one), "without the feet only the list itself is looked at");
    }

    @Test
    void aSmallerMarginStaysAsItIs() {
        assertEquals(0.2, ReachMargin.of(FEET, HALL_ENTRY, 0.2), 1e-9);
    }
}

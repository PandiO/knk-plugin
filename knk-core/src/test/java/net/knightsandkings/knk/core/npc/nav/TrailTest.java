package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.WORLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Link B2 (PandiO/knk-workspace#671, decision 34.1): the NPC's place on its waypoint list, the handover's 3D measure
 * and the next stretch appended to the waypoints not yet walked. The stacked hairpin: a flight going north along
 * x = 0 from feet y 65 to 69, a landing, and the next flight going back south along x = 2 from y 69 to 73, right
 * above the first.
 */
class TrailTest {

    private static NavPoint feet(double x, double y, double z) {
        return new NavPoint(WORLD, x, y, z);
    }

    /** The first flight: north (decreasing z), a block up per block along. */
    private static final List<NavPoint> FLIGHT = List.of(feet(0.5, 65, 4.5), feet(0.5, 66, 3.5), feet(0.5, 67, 2.5),
        feet(0.5, 68, 1.5), feet(0.5, 69, 0.5));
    /** The next flight: back south along x = 2, from the landing up to y 73. */
    private static final List<NavPoint> NEXT = List.of(feet(1.5, 69, -0.5), feet(2.5, 69, 0.5),
        feet(2.5, 70, 1.5), feet(2.5, 71, 2.5), feet(2.5, 72, 3.5), feet(2.5, 73, 4.5));

    @Test
    void startsAtTheFirstWaypointAndMovesOnAsTheFeetPassThem() {
        Trail trail = new Trail(FLIGHT);
        assertEquals(0, trail.index());

        trail.update(feet(0.5, 65, 4.6));
        assertEquals(1, trail.index(), "within reach of the first waypoint: on to the second");

        trail.update(feet(0.5, 66.4, 3.0)); // past the second, between it and the third
        assertEquals(2, trail.index());
        assertEquals(FLIGHT.subList(2, FLIGHT.size()), trail.remaining());
    }

    @Test
    void neverMovesBackAndAllWalkedLeavesNothing() {
        Trail trail = new Trail(FLIGHT);
        trail.update(feet(0.5, 68, 1.6));
        int at = trail.index();
        trail.update(feet(0.5, 65, 4.5)); // pushed back down the flight
        assertEquals(at, trail.index());

        trail.update(feet(0.5, 69, 0.5));
        assertTrue(trail.remaining().isEmpty());
        assertEquals(0, trail.rest(feet(0.5, 69, 0.5)), 1e-9);
    }

    @Test
    void theFlightAboveIsNotTakenForTheNpcsOwnPlace() {
        // the list goes up the first flight and back over it on the next: points of the next flight lie right above
        List<NavPoint> both = new java.util.ArrayList<>(FLIGHT);
        both.addAll(NEXT);
        Trail trail = new Trail(both);

        trail.update(feet(2.5, 66, 3.5)); // at the foot of the first flight, under the next flight's x = 2.5, z = 3.5
        assertTrue(trail.index() <= 2, "a point 6 blocks up is not where it is: " + trail.index());
    }

    @Test
    void restIsMeasuredInThreeDimensionsAlongTheWaypointsLeft() {
        Trail trail = new Trail(FLIGHT);
        trail.update(feet(0.5, 66, 3.5));
        NavPoint at = feet(0.5, 66, 3.5);

        // horizontally the flight's end is 3 blocks off; along the steps, up and north, it is 3 * sqrt(2)
        assertEquals(3, at.horizontalDistance(FLIGHT.get(4)), 1e-9);
        assertEquals(3 * Math.sqrt(2), trail.rest(at), 1e-6);
    }

    @Test
    void aStretchEndRightAboveTheNpcIsStillAFlightAway() {
        // decision 34.1: the old check (horizontal distance under 4) handed the next stretch out here
        Trail trail = new Trail(NEXT);
        NavPoint under = feet(2.5, 66, 4.5); // 7 blocks right under the next flight's end
        assertTrue(under.horizontalDistance(NEXT.get(NEXT.size() - 1)) < NpcNavigationService.HANDOVER_DISTANCE);
        assertTrue(trail.rest(under) >= NpcNavigationService.HANDOVER_DISTANCE, "rest " + trail.rest(under));
    }

    @Test
    void theNextStretchIsAppendedToTheWaypointsNotYetWalked() {
        Trail trail = new Trail(FLIGHT);
        trail.update(feet(0.5, 67, 2.6));

        List<NavPoint> out = trail.appended(NEXT);

        assertEquals(FLIGHT.get(3), out.get(0), "the waypoint it walks to stays first");
        assertEquals(FLIGHT.get(4), out.get(1), "the landing is not skipped");
        assertEquals(NEXT, out.subList(2, out.size()));
    }

    @Test
    void aDuplicateAtTheJoinIsDropped() {
        Trail trail = new Trail(FLIGHT);
        List<NavPoint> more = List.of(FLIGHT.get(4), NEXT.get(0));

        List<NavPoint> out = trail.appended(more);

        assertEquals(FLIGHT.size() + 1, out.size());
        assertEquals(NEXT.get(0), out.get(out.size() - 1));
    }
}

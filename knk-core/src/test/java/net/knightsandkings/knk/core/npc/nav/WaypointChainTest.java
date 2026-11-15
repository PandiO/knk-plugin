package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.WORLD;
import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Waypoint steps stay straight walks on floor (PandiO/knk-workspace#592): an L-shaped walk at floor 64 round a drop
 * (floor 57 everywhere else), the walk going south along x = 0 to z = 3, then east along z = 3.
 */
class WaypointChainTest {

    private final FakeNavWorld world = new FakeNavWorld();

    WaypointChainTest() {
        world.floor = (x, z) -> (x == 0 && z >= 0 && z <= 3) || (z == 3 && x >= 0 && x <= 6) ? 64 : 57;
    }

    private static NavPoint feet(double x, double y, double z) {
        return new NavPoint(WORLD, x, y, z);
    }

    @Test
    void aDiagonalAcrossTheDropGetsTheCornerCell() {
        List<NavPoint> out = WaypointChain.bridged(world, null, List.of(feet(0.5, 65, 0.5), feet(4.5, 65, 3.5)));

        assertEquals(List.of(feet(0.5, 65, 0.5), feet(0.5, 65, 3.5), feet(4.5, 65, 3.5)), out);
        for (int i = 1; i < out.size(); i++) {
            assertTrue(NavGeometry.straightWalkable(world, out.get(i - 1), out.get(i)), out.toString());
        }
    }

    @Test
    void stepsThatAreStraightWalksAreLeftAlone() {
        List<NavPoint> in = List.of(feet(0.5, 65, 0.5), feet(0.5, 65, 3.5), feet(6.5, 65, 3.5));

        assertEquals(in, WaypointChain.bridged(world, null, in));
    }

    @Test
    void theStepFromWhereTheWalkStartsIsBridgedTooButNotHandedOut() {
        List<NavPoint> out = WaypointChain.bridged(world, feet(0.5, 65, 1.5), List.of(feet(3.5, 65, 3.5)));

        assertEquals(List.of(feet(0.5, 65, 3.5), feet(3.5, 65, 3.5)), out);
    }

    @Test
    void withNoWalkBetweenTheStepStaysAsItWas() {
        world.floor = (x, z) -> (x == 0 && z == 0) || (x == 5 && z == 5) ? 64 : 57; // two islands
        List<NavPoint> in = List.of(feet(0.5, 65, 0.5), feet(5.5, 65, 5.5));

        assertEquals(in, WaypointChain.bridged(world, null, in));
    }

    @Test
    void theBridgeKeepsToFloorsWithinAStepAndTheBodysHeadroom() {
        // a low ceiling over the corner cell: the walk goes round by a cell that has headroom
        world.floor = (x, z) -> (x <= 1 && z >= 0 && z <= 3) || (z >= 2 && z <= 3 && x >= 0 && x <= 6) ? 64 : 57;
        world.headroom.put("0,64,3", 1.0);
        List<NavPoint> out = WaypointChain.bridged(world, null, List.of(feet(0.5, 65, 0.5), feet(4.5, 65, 3.5)));

        for (NavPoint p : out) {
            assertTrue(!(p.blockX() == 0 && p.blockZ() == 3), "no waypoint under the low ceiling: " + out);
        }
        for (int i = 1; i < out.size(); i++) {
            assertTrue(NavGeometry.straightWalkable(world, out.get(i - 1), out.get(i)), out.toString());
        }
    }

    @Test
    void surfaceNearMovesAPointOverTheDropOntoTheWalkBesideIt() {
        NavPoint p = HopPlanner.surfaceNear(world, floor(1.2, 64, 1.5), 64);
        assertEquals(0.5, p.x(), 1e-9);
        assertEquals(64, p.y(), 1e-9);

        assertNull(HopPlanner.surfaceNear(world, floor(3.5, 64, 0.5), 64), "no walk within a block: nothing");
        assertEquals(64, HopPlanner.surfaceNear(world, floor(0.4, 64, 2.0), 64).y(), 1e-9, "on the walk: itself");
    }
}

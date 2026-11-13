package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class HopPlannerTest {

    private final FakeNavWorld world = new FakeNavWorld();

    @Test
    void resampleKeepsTheSpacingAndEndsAtTheLastPoint() {
        List<NavPoint> hops = HopPlanner.resample(List.of(floor(0, 64, 0), floor(40, 64, 0)), 16);

        assertEquals(3, hops.size());
        assertEquals(16, hops.get(0).x(), 1e-9);
        assertEquals(32, hops.get(1).x(), 1e-9);
        assertEquals(40, hops.get(2).x(), 1e-9);
    }

    @Test
    void spacingCarriesOverCornersAndTheEndIsNotDoubled() {
        // 10 east, then 22 south: 32 blocks in all, a point every 16
        List<NavPoint> hops = HopPlanner.resample(List.of(floor(0, 64, 0), floor(10, 64, 0), floor(10, 64, 22)), 16);

        assertEquals(2, hops.size());
        assertEquals(10, hops.get(0).x(), 1e-9);
        assertEquals(6, hops.get(0).z(), 1e-9);
        assertEquals(22, hops.get(1).z(), 1e-9);
    }

    @Test
    void anEmptyLineHasNoHopsAndTheSpacingMustBePositive() {
        assertTrue(HopPlanner.resample(List.of(), 16).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> HopPlanner.resample(List.of(floor(0, 64, 0)), 0));
    }

    @Test
    void cornersAreKeptWhenAskedFor() {
        List<NavPoint> line = HopPlanner.resampleKeepingCorners(
            List.of(floor(0, 64, 0), floor(4, 64, 0), floor(4, 64, 4)), 1.5);

        assertEquals(floor(0, 64, 0), line.get(0));
        assertTrue(line.contains(floor(4, 64, 0)), "the corner itself is a point of the line");
        assertEquals(floor(4, 64, 4), line.get(line.size() - 1));
        for (int i = 1; i < line.size(); i++) {
            assertTrue(line.get(i - 1).horizontalDistance(line.get(i)) <= 1.5 + 1e-9);
        }
    }

    @Test
    void hopsOnStairsAreSnappedToTheStepUnderThem() {
        // a staircase rising one block per block east; the line only knows its two ends
        world.floor = (x, z) -> 64 + Math.max(0, Math.min(20, x));
        List<NavPoint> hops = HopPlanner.hops(world, List.of(floor(0.5, 64, 0.5), floor(20.5, 84, 0.5)), 8);

        assertTrue(hops.size() >= 3);
        for (NavPoint hop : hops) {
            assertEquals(64 + hop.blockX(), hop.y(), 1e-9, "hop at x " + hop.x() + " stands on its step");
        }
    }

    @Test
    void theEndIsSnappedToTheFloorToo() {
        world.floor = (x, z) -> 70;
        List<NavPoint> hops = HopPlanner.hops(world, List.of(floor(0, 64, 0), floor(10, 64, 0)), 16);

        assertEquals(1, hops.size());
        assertEquals(70, hops.get(0).y(), 1e-9);
        assertEquals(10, hops.get(0).x(), 1e-9);
    }

    @Test
    void snapToFloorTakesTheNearestFloorAndGivesUpBeyondItsRange() {
        world.floor = (x, z) -> 60;
        assertEquals(60, HopPlanner.snapToFloor(world, floor(0, 64, 0)).y(), 1e-9);

        world.floor = (x, z) -> 64 - HopPlanner.SNAP_RANGE - 1;
        NavPoint inTheAir = floor(0, 64, 0);
        assertSame(inTheAir, HopPlanner.snapToFloor(world, inTheAir));
    }

    @Test
    void anUnloadedColumnIsLeftAsItIs() {
        world.loaded = (x, z) -> false;
        NavPoint point = floor(0, 80, 0);

        assertSame(point, HopPlanner.snapToFloor(world, point));
        assertSame(point, HopPlanner.snapNear(world, point, 64));
    }

    @Test
    void snapNearStaysOnTheFlightTheNpcIsOn() {
        // two flights of a U-turn staircase above each other: floors at 50 and 56 in the same column
        world.floor = (x, z) -> 50;
        world.addFloor(3, 56, 3);

        // the interpolated height is nearer the upper flight, but the previous waypoint was on the lower one
        assertEquals(50, HopPlanner.snapNear(world, floor(3.5, 55, 3.5), 50).y(), 1e-9);
        // coming along the upper flight, the same point is on the upper one
        assertEquals(56, HopPlanner.snapNear(world, floor(3.5, 55, 3.5), 55).y(), 1e-9);
    }

    @Test
    void feetAndFloorAreOneBlockApart() {
        NavPoint feet = HopPlanner.feet(floor(1.5, 64, 2.5));
        assertEquals(65, feet.y(), 1e-9);
        assertEquals(floor(1.5, 64, 2.5), HopPlanner.floor(feet));
    }
}

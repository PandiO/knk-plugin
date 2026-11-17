package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import net.knightsandkings.knk.core.navigation.shape.NavGeometry;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;

/**
 * Waypoints at the real walking surface (link B4, PandiO/knk-workspace#673): a bottom slab's top is half a block
 * under the whole-block feet height navigation plans with, and a half-block step up is no jump.
 */
class NavGeometrySurfaceTest {

    private final FakeNavWorld world = new FakeNavWorld();

    /** A half-block stair along x: x 0 full block at 64, x 1 slab at 65, x 2 full at 65, x 3 slab at 66, ... */
    private void halfBlockStair() {
        world.floor = (x, z) -> x <= 0 ? 64 : 64 + (x + 1) / 2;
        for (int x = 1; x <= 9; x += 2) {
            world.tops.put(x + "," + (64 + (x + 1) / 2) + ",0", 0.5);
        }
    }

    @Test
    void theFloorUnderAFeetHeight() {
        assertEquals(72, NavGeometry.floorYOf(73), "whole-block feet stand on the block below");
        assertEquals(72, NavGeometry.floorYOf(72.5), "feet on a slab stand in the slab's block");
        assertEquals(71, NavGeometry.floorYOf(72.004), "a hair over a full block is still that block");
        assertEquals(72, NavGeometry.floorYOf(72.9375), "a stair's or a path block's top");
    }

    @Test
    void aWaypointOnASlabGoesToTheSlabsTop() {
        halfBlockStair();

        NavPoint onSlab = NavGeometry.onSurface(world, floor(1.5, 66, 0.5)); // floor + 1 over the slab at 65

        assertEquals(65.5, onSlab.y(), 1e-9);
        assertEquals(1.5, onSlab.x(), 1e-9);
        assertEquals(0.5, onSlab.z(), 1e-9);
    }

    @Test
    void aWaypointOnAFullBlockStaysAsItIs() {
        halfBlockStair();
        NavPoint full = floor(2.5, 66, 0.5);

        assertSame(full, NavGeometry.onSurface(world, full));
    }

    @Test
    void itIsIdempotentAndLeavesWaypointsWithoutAFloorAlone() {
        halfBlockStair();
        NavPoint once = NavGeometry.onSurface(world, floor(3.5, 67, 0.5));
        NavPoint inAir = floor(3.5, 70, 0.5);

        assertEquals(66.5, once.y(), 1e-9);
        assertSame(once, NavGeometry.onSurface(world, once), "a slab's top stays a slab's top");
        assertSame(inAir, NavGeometry.onSurface(world, inAir), "no floor under it: left as it is");
    }

    @Test
    void aListComesBackAsItIsWhenNothingChanges() {
        List<NavPoint> flat = List.of(floor(0.5, 65, 0.5), floor(1.5, 65, 0.5));

        assertSame(flat, NavGeometry.onSurface(world, flat));
    }

    @Test
    void aListUpTheHalfBlockStairRisesHalfABlockAStep() {
        halfBlockStair();
        List<NavPoint> planned = List.of(floor(0.5, 65, 0.5), floor(1.5, 66, 0.5), floor(2.5, 66, 0.5),
            floor(3.5, 67, 0.5), floor(4.5, 67, 0.5));

        List<NavPoint> sent = NavGeometry.onSurface(world, planned);

        double[] expected = {65, 65.5, 66, 66.5, 67};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], sent.get(i).y(), 1e-9, "waypoint " + i);
        }
        for (int i = 1; i < sent.size(); i++) {
            assertTrue(sent.get(i).y() - sent.get(i - 1).y() <= NavGeometry.STEP_UP, "every step a step, no jump");
        }
    }

    @Test
    void aHalfBlockStepUpFitsUnderLowHeadroomAWholeBlockDoesNot() {
        halfBlockStair();
        world.headroom.put("0,64,0", 2.0); // under the flight above: too low to jump from
        world.headroom.put("2,65,0", 2.0);

        assertTrue(NavGeometry.jumpFits(world, floor(2.5, 65, 0.5), floor(3.5, 66, 0.5)),
            "full block at 65 (top 66) onto the slab at 66 (top 66.5): half a block, a step");
        world.tops.clear();
        assertFalse(NavGeometry.jumpFits(world, floor(2.5, 65, 0.5), floor(3.5, 66, 0.5)),
            "a whole block up needs the jump's headroom");
    }

    @Test
    void aStepSkippedBetweenTwoWaypointsGoesIn() {
        // full block at 64 (x 0), a stair's step at 65 (x 1, top 65.5), full block at 65 (x 2): waypoints at x 0 and x 2
        // only would leave a target a whole block up within a block of the feet, and the engine jumps
        world.floor = (x, z) -> x <= 0 ? 64 : 65;
        world.tops.put("1,65,0", 0.5);

        List<NavPoint> sent = NavGeometry.onSurface(world, List.of(floor(0.5, 65, 0.5), floor(2.5, 66, 0.5)));

        assertEquals(3, sent.size(), "the step goes in: " + sent);
        assertEquals(65.5, sent.get(1).y(), 1e-9);
        assertEquals(1, sent.get(1).blockX(), "in the step's cell, on the line");
        assertEquals(0.5, sent.get(1).z(), 1e-9);
        assertEquals(66, sent.get(2).y(), 1e-9);
    }

    @Test
    void aWholeBlockFlightGetsTheBlockBetweenAndNeighboursNothing() {
        world.floor = (x, z) -> x <= 0 ? 64 : x == 1 ? 65 : 66;
        List<NavPoint> planned = List.of(floor(0.5, 65, 0.5), floor(2.5, 67, 0.5));

        List<NavPoint> sent = NavGeometry.onSurface(world, planned);

        assertEquals(3, sent.size(), "the block step between: " + sent);
        assertEquals(66, sent.get(1).y(), 1e-9);
        List<NavPoint> neighbours = List.of(floor(0.5, 65, 0.5), floor(1.5, 66, 0.5));
        assertSame(neighbours, NavGeometry.onSurface(world, neighbours), "neighbours a block apart: nothing between");
    }

    @Test
    void theStepFromTheFeetToTheFirstWaypointGoesInToo() {
        world.floor = (x, z) -> x <= 0 ? 64 : 65;
        world.tops.put("1,65,0", 0.5);
        List<NavPoint> planned = List.of(floor(2.5, 66, 0.5));

        List<NavPoint> sent = NavGeometry.onSurface(world, floor(0.5, 65, 0.5), planned);

        assertEquals(2, sent.size(), "the step, then the waypoint: " + sent);
        assertEquals(65.5, sent.get(0).y(), 1e-9);
        assertSame(planned, NavGeometry.onSurface(world, null, planned), "without the feet: as it is");
    }
}

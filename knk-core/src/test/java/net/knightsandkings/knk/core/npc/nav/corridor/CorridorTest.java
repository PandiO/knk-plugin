package net.knightsandkings.knk.core.npc.nav.corridor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NavPoint;

/**
 * The corridor's clearance map, a distance transform over the strip round the road line (implementation plan 4c.4 item
 * 2.2, link M2): jagged walls, a post beside the road, a doorway, a drop, another level, another region.
 */
class CorridorTest {

    private final GridWorld world = new GridWorld();

    /** A straight east along z 0.5 from x 0 to x 20. */
    private List<NavPoint> straight() {
        return List.of(world.feet(0.5, 0.5), world.feet(10.5, 0.5), world.feet(20.5, 0.5));
    }

    @Test
    void aThreeWideRoadHasOneAndAHalfInItsMiddleAndAHalfAtItsSides() {
        world.walls(-5, -2, 25, -2).walls(-5, 2, 25, 2);

        Corridor c = Corridor.build(world, straight(), 4);

        assertEquals(1.5, c.cellClearance(10, 0), 1e-6);
        assertEquals(0.5, c.cellClearance(10, 1), 1e-6);
        assertEquals(0.5, c.cellClearance(10, -1), 1e-6);
        assertEquals(0.8, c.clearanceAt(10.5, -0.2), 1e-6, "0.8 from the north wall's face at z -1");
        assertFalse(c.contains(10, 2), "the wall");
        assertFalse(c.contains(10, 3), "behind the wall: not reached from the line");
        FreeWidth w = c.freeWidth(world.feet(10.5, 0.5), 1, 0);
        assertEquals(3.0, w.total(), 0.11);
    }

    @Test
    void aJaggedWallPieceIsSeenFromTheSideAndTheCorner() {
        world.walls(-5, -2, 25, -2).walls(-5, 3, 25, 3).wall(10, 2); // 4 wide, one piece juts out at x 10

        Corridor c = Corridor.build(world, straight(), 4);

        assertEquals(0.5, c.cellClearance(10, 1), 1e-6, "right beside the jutting piece");
        assertEquals(0.5, c.cellClearance(9, 2), 1e-6, "next to it in the notch");
        assertEquals(Math.hypot(0.5, 0.5), c.cellClearance(9, 1), 1e-6, "diagonal to its corner");
    }

    @Test
    void aPostBesideTheRoadIsAnObstacleOnOpenGround() {
        world.wall(10, 2);

        Corridor c = Corridor.build(world, straight(), 4);

        assertEquals(0.5, c.cellClearance(10, 1), 1e-6);
        assertEquals(1.5, c.cellClearance(10, 0), 1e-6);
        assertEquals(Corridor.CAP, c.cellClearance(3, 0), 1e-6, "open ground: the strip's end is no wall");
    }

    @Test
    void aOneBlockDoorwayKeepsHalfABlockAndStaysOpen() {
        world.walls(-5, 5, 25, 5);
        world.walls.remove(GridWorld.key(10, 5));
        List<NavPoint> line = List.of(world.feet(10.5, 1.5), world.feet(10.5, 5.5), world.feet(10.5, 9.5));

        Corridor c = Corridor.build(world, line, 4);

        assertTrue(c.contains(10, 5));
        assertEquals(0.5, c.cellClearance(10, 5), 1e-6);
        assertEquals(1.0, c.freeWidth(world.feet(10.5, 5.5), 0, 1).total(), 0.11);
    }

    @Test
    void aDropCountsLikeAWall() {
        for (int x = -5; x <= 25; x++) {
            world.hole(x, 2);
        }

        Corridor c = Corridor.build(world, straight(), 4);

        assertFalse(c.contains(10, 2));
        assertEquals(0.5, c.cellClearance(10, 1), 1e-6);
    }

    @Test
    void aStepIsWalkedButTwoBlocksUpIsAWall() {
        for (int x = -5; x <= 25; x++) {
            world.floorAt(x, 1, 65); // a step up, walkable
            world.floorAt(x, 2, 67); // two more up: a wall for the step and the road
        }

        Corridor c = Corridor.build(world, straight(), 4);

        assertEquals(65, c.floorAt(10, 1));
        assertFalse(c.contains(10, 2));
        assertEquals(0.5, c.cellClearance(10, 1), 1e-6);
    }

    @Test
    void aRegionTheLineNeverEntersIsAnObstacle() {
        world.regions.put(GridWorld.key(10, 2), Set.of("keep"));

        Corridor c = Corridor.build(world, straight(), 4);

        assertFalse(c.contains(10, 2));
        assertEquals(0.5, c.cellClearance(10, 1), 1e-6);
    }

    @Test
    void cellsBeyondTheHalfWidthAreNotLookedAt() {
        Corridor c = Corridor.build(world, straight(), 2);

        assertTrue(c.contains(10, 2));
        assertFalse(c.contains(10, 4));
        assertEquals(Corridor.CAP, c.cellClearance(10, 0), 1e-6);
    }
}

package net.knightsandkings.knk.core.npc.nav.group;

import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.feet;
import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.floor;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.SimWorld;

/** The path a group walks: distances along it, lanes beside it and where it is too narrow for them. */
class GroupPathTest {

    private final SimWorld world = new SimWorld();

    /** East along z = 0.5 from x = 0.5, a point every 1.5 blocks. */
    private GroupPath east(int points) {
        GroupPath path = new GroupPath();
        for (int i = 0; i < points; i++) {
            path.add(floor(0.5 + 1.5 * i, 0.5));
        }
        return path;
    }

    @Test
    void distancesAlongThePathAreHorizontal() {
        GroupPath path = east(5);

        assertEquals(0, path.arc(0));
        assertEquals(6, path.endArc(), 1e-9);
        assertEquals(2, path.indexAtArc(3.2));
        assertEquals(0, path.indexAtArc(-4));
        assertEquals(4, path.indexAtArc(99));
        assertEquals(3, path.nearest(feet(5.2, 3), 0, path.size()));
        assertEquals(1, path.nearest(feet(5.2, 3), 0, 2), "only the points asked for");
        assertEquals(4.7, path.arcOf(feet(5.2, 3), 3), 1e-9, "the point's distance plus the step along the path");
        assertEquals(8.5, path.arcOf(feet(9.0, 0.5), 4), 1e-9, "past the last point the distance goes on");
        assertEquals(-2, path.arcOf(feet(-1.5, 0.5), 0), 1e-9, "and before the first");
        assertEquals(3 + 1, path.arcOf(feet(9.0, 0.5), 2), 1e-9, "but not past a point in the middle");
    }

    @Test
    void theHeadingFollowsThePathAroundACorner() {
        GroupPath path = new GroupPath();
        path.add(floor(0.5, 0.5));
        path.add(floor(3.5, 0.5));
        path.add(floor(3.5, 0.5)); // a doubled point has no direction of its own
        path.add(floor(3.5, 3.5));
        path.add(floor(3.5, 6.5));

        assertArrayEquals(new double[] {1, 0}, path.heading(0), 1e-9);
        assertArrayEquals(new double[] {Math.sqrt(0.5), Math.sqrt(0.5)}, path.heading(1), 1e-9);
        assertArrayEquals(new double[] {0, 1}, path.heading(4), 1e-9);
        GroupPath single = new GroupPath();
        single.add(floor(0.5, 0.5));
        assertArrayEquals(new double[] {1, 0}, single.heading(0), 1e-9);
    }

    @Test
    void aLanePointLiesToTheRightOfTheHeading() {
        GroupPath path = east(4);

        assertEquals(new NavPoint(GroupSim.WORLD, 2.0, 65, 2.5), path.point(world, 1, 0, 2), "east: right is +z");
        assertEquals(new NavPoint(GroupSim.WORLD, 2.0, 65, -1.0), path.point(world, 1, 0, -1.5));
        assertEquals(new NavPoint(GroupSim.WORLD, 2.6, 65, 0.5), path.point(world, 1, 0.6, 0), "along the path");
        assertEquals(new NavPoint(GroupSim.WORLD, 7.5, 65, 1.5), path.point(world, 3, 2.5, 1), "and past its end");
    }

    @Test
    void aLaneNeedsAFloorAllTheWayOutFromThePath() {
        GroupPath path = east(4);
        world.noFloor = (x, z) -> x == 2 && z == 1; // a post right beside the path at the second point

        assertNull(path.point(world, 1, 0, 1), "no floor in the lane");
        assertNull(path.point(world, 1, 0, 2), "nor behind something that stands between the path and the lane");
        assertEquals(new NavPoint(GroupSim.WORLD, 2.0, 65, -0.5), path.point(world, 1, 0, -1), "the other side is free");
        assertFalse(path.wide(world, 1, 1));
        assertTrue(path.wide(world, 0, 1));
        assertTrue(path.wide(world, 2, 1));
        world.noFloor = (x, z) -> false;
        assertFalse(path.wide(world, 1, 1), "a point is looked at once per width");
        assertNull(path.point(world, 1, 0, 1), "and a lane once per side");
        assertTrue(path.wide(world, 1, 3), "another width is looked at afresh");
    }

    @Test
    void aLaneNeverLiesInOtherRegionsThanThePath() {
        GroupPath path = east(4);
        world.regions = (x, z) -> z >= 2 ? Set.of("yard") : Set.of();

        assertEquals(new NavPoint(GroupSim.WORLD, 2.0, 65, 1.5), path.point(world, 1, 0, 1));
        assertNull(path.point(world, 1, 0, 2), "the lane would be in the yard, the path is not");
        assertFalse(path.wide(world, 1, 2));
        assertEquals(Set.of(), path.regions(world, 1));
    }

    @Test
    void routePointsAreMovedOntoTheFloorNearThePreviousOne() {
        GroupPath path = new GroupPath();

        path.addSnapped(world, new NavPoint(GroupSim.WORLD, 0.5, 66, 0.5), 64); // the line says 66, the floor is at 64
        path.addSnapped(world, new NavPoint(GroupSim.WORLD, 2.0, 63, 0.5), 80);

        assertEquals(64, path.get(0).floor.y());
        assertEquals(64, path.get(1).floor.y());
        path.truncate(1);
        assertEquals(1, path.size());
        path.clear();
        assertTrue(path.isEmpty());
        assertEquals(0, path.endArc());
    }
}

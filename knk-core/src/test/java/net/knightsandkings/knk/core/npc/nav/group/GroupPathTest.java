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
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics;
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
    void aLinePointOverADropMovesOntoTheWalkBesideItOrRepeatsThePointBefore() {
        world.noFloor = (x, z) -> z != 0; // a 1-wide walk along z = 0, a drop either side (#592)
        GroupPath path = new GroupPath();
        path.addSnapped(world, new NavPoint(GroupSim.WORLD, 1.5, 64, 0.5), 64);

        path.addSnapped(world, new NavPoint(GroupSim.WORLD, 2.5, 64, 1.4), 64);
        assertEquals(2.5, path.get(1).floor.x(), 1e-9);
        assertEquals(0.5, path.get(1).floor.z(), 1e-9, "onto the walk beside it");

        int index = path.addSnapped(world, new NavPoint(GroupSim.WORLD, 3.5, 64, 3.5), 64);
        assertEquals(2, index, "one path point per line point");
        assertEquals(path.get(1).floor, path.get(2).floor, "no walk within a block: the point before again");
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

    // ==================== widths per point (implementation plan 4b.3 items 4 and 6) ====================

    @Test
    void eachPointKnowsTheWidestWidthThatFitsLookedUpOnce() {
        GroupPath path = east(6);
        path.levels(new double[] {1.0, 0.5, 0}); // 3 abreast, 2 abreast, the column at lateral spacing 1
        int[] looks = {0};
        // a 3-wide road (z -1..1) narrowing to 2 (z 0..1) at x 4 and 1 (z 0) at x 6; the line runs at z 0.5
        world.noFloor = (x, z) -> {
            looks[0]++;
            return z < -1 || z > 1 || (x >= 4 && z < 0) || (x >= 6 && z > 0);
        };

        int[] widest = new int[path.size()];
        for (int i = 0; i < path.size(); i++) {
            widest[i] = path.widest(world, i);
        }
        int first = looks[0];
        for (int i = 0; i < path.size(); i++) {
            assertEquals(widest[i], path.widest(world, i));
            path.shift(world, i, widest[i]);
        }

        assertEquals(first, looks[0], "a point's widths are looked up once");
        assertTrue(first <= path.size() * 3 * 12, "a bounded number of world looks per point and width, saw " + first);
        assertEquals(0, widest[0]);
        assertEquals(0, widest[2], "x 3.5: 3 wide");
        assertEquals(1, widest[3], "x 5: 2 wide");
        assertEquals(2, widest[4], "x 6.5: 1 wide");
        assertEquals(0.0, path.shift(world, 0, 0), "a road centred on its line: no shift");
        assertEquals(0.0, path.shift(world, 0, 1), "2 abreast on a 3-wide road: no shift");
        assertEquals(0.5, path.shift(world, 3, 1),
            "2 abreast in a 2-wide passage whose line runs down one column: block centres, not the block edge");
        assertTrue(Double.isNaN(path.shift(world, 3, 0)), "3 abreast do not fit there");
        assertEquals(0.0, path.shift(world, 4, 2), "the column needs no shift");
        assertNull(path.point(world, 4, 0, -0.5),
            "a lane on the block edge of a 1-wide passage has a floor under it but a wall at its shoulder");
    }

    @Test
    void newWidthsForgetWhatWasWorkedOut() {
        GroupPath path = east(3);
        path.levels(new double[] {1.0, 0});
        world.noFloor = (x, z) -> z > 1 || z < -1;
        assertEquals(0, path.widest(world, 1));
        path.levels(new double[] {2.0, 0});
        assertEquals(1, path.widest(world, 1), "a 3-wide road is too narrow for a half width of 2");
        assertEquals(2, path.levelCount());
    }

    @Test
    void aFlightOfStairsIsWalkedAsAColumnHoweverWide() {
        GroupPath path = new GroupPath();
        path.levels(new double[] {0.5, 0});
        path.add(floor(0.5, 0.5));
        path.add(floor(1.5, 0.5));
        path.add(new NavPoint(GroupSim.WORLD, 2.5, 65, 0.5)); // a step up
        path.add(new NavPoint(GroupSim.WORLD, 3.5, 65, 0.5));
        path.add(new NavPoint(GroupSim.WORLD, 4.5, 65, 0.5));
        world.noFloor = (x, z) -> false;

        assertEquals(0, path.widest(world, 0), "flat: 2 abreast");
        assertTrue(path.stair(1));
        assertEquals(1, path.widest(world, 1), "the foot of the step");
        assertEquals(1, path.widest(world, 2), "the step");
        assertFalse(path.stair(3));
    }

    // ==================== fold reasons (implementation plan 4c.3 decision 2.8) ====================

    @Test
    void aStepNamesTheStairAsTheReason() {
        GroupPath path = new GroupPath();
        path.levels(new double[] {0.5, 0});
        path.add(floor(0.5, 0.5));
        path.add(floor(1.5, 0.5));
        path.add(new NavPoint(GroupSim.WORLD, 2.5, 65, 0.5)); // a step up

        assertEquals(NpcNavigationMetrics.FoldReason.STAIR, path.misfitReason(world, 1, 0));
        assertEquals(NpcNavigationMetrics.FoldReason.STAIR, path.misfitReason(world, 2, 0));
    }

    @Test
    void noFloorBesideThePathNamesTheNarrowWalk() {
        GroupPath path = east(4);
        path.levels(new double[] {1.0, 0});
        world.noFloor = (x, z) -> z != 0; // 1 wide

        assertEquals(1, path.widest(world, 1));
        assertEquals(NpcNavigationMetrics.FoldReason.NARROW, path.misfitReason(world, 1, 0));
    }

    @Test
    void aLaneInAnotherRegionNamesTheRules() {
        GroupPath path = east(4);
        path.levels(new double[] {1.0, 0});
        world.noFloor = (x, z) -> z < -1 || z > 2;
        world.regions = (x, z) -> z >= 1 ? Set.of("yard") : Set.of();

        assertEquals(1, path.widest(world, 1), "every shift puts a lane in the yard or off the floor");
        assertEquals(NpcNavigationMetrics.FoldReason.RULES, path.misfitReason(world, 1, 0),
            "the nearest try failed on the yard, not on the floor");
    }

    @Test
    void aLaneWithFloorButNoRoomAtItsShoulderNamesTheEdge() {
        GroupPath path = east(4);
        path.levels(new double[] {1.4, 0});
        world.noFloor = (x, z) -> z < -1 || z > 1; // 3 wide: lanes at z 1.9 and -0.9 stand on floor, their shoulders do not

        assertEquals(1, path.widest(world, 1));
        assertEquals(NpcNavigationMetrics.FoldReason.EDGE, path.misfitReason(world, 1, 0));
    }
}

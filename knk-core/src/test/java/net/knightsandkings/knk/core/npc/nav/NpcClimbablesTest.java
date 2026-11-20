package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.WORLD;
import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.navigation.shape.NavWorld;
import net.knightsandkings.knk.core.npc.nav.RouteFollower.Kind;
import net.knightsandkings.knk.core.npc.nav.RouteFollower.Stretch;
import net.knightsandkings.knk.core.roads.walk.WalkCells;

/**
 * Link H1 (PandiO/knk-workspace#779): ladders and roof hatches on the NPC side agree with the walk search's cells
 * ({@code WalkCells.isClimbableHatch}), and the route line and the waypoint chain keep a walk's ladder cells.
 */
class NpcClimbablesTest {

    private static final Set<String> LADDERS = Set.of("LADDER");

    // ---- the rules by material name ----

    @Test
    void aLadderAndAnOpenHatchOverOneAreClimbed() {
        assertTrue(NpcClimbables.climbable("LADDER", false, null, LADDERS));
        assertTrue(NpcClimbables.climbable("OAK_TRAPDOOR", true, "LADDER", LADDERS));
        assertTrue(NpcClimbables.climbable("IRON_TRAPDOOR", true, "LADDER", LADDERS), "open is open, iron or not");
        assertFalse(NpcClimbables.climbable("OAK_TRAPDOOR", false, "LADDER", LADDERS), "a closed hatch is solid");
        assertFalse(NpcClimbables.climbable("OAK_TRAPDOOR", true, "STONE", LADDERS), "no ladder below: no hatch");
        assertFalse(NpcClimbables.climbable("STONE", false, null, LADDERS));
        assertFalse(NpcClimbables.climbable("LADDER", false, null, Set.of()), "only the climbables given");
        assertEquals(Set.of("LADDER"), NpcClimbables.DEFAULT, "the walk profile's default");
    }

    @Test
    void aClosedHatchOverALadderIsOneAHandOpens() {
        assertTrue(NpcClimbables.closedHatch("SPRUCE_TRAPDOOR", false, "LADDER", LADDERS));
        assertFalse(NpcClimbables.closedHatch("SPRUCE_TRAPDOOR", true, "LADDER", LADDERS), "open already");
        assertFalse(NpcClimbables.closedHatch("IRON_TRAPDOOR", false, "LADDER", LADDERS), "iron needs redstone");
        assertFalse(NpcClimbables.closedHatch("SPRUCE_TRAPDOOR", false, "AIR", LADDERS));
        assertFalse(NpcClimbables.closedHatch("SPRUCE_TRAPDOOR", false, null, LADDERS));
        assertFalse(NpcClimbables.closedHatch("OAK_DOOR", false, "LADDER", LADDERS));
    }

    // ---- the walk search's cells for an NPC that opens doors ----

    /** A ladder column at (0, 64..70, 0) under a closed oak hatch at y 71, and a closed iron hatch over (2, 64..70, 0). */
    private static WalkCells captured() {
        return new WalkCells() {
            @Override
            public boolean isDoor(int x, int y, int z) {
                return x == 5 && y == 65;
            }

            @Override
            public boolean isClimbable(int x, int y, int z) {
                return (x == 0 || x == 2) && z == 0 && y >= 64 && y <= 70;
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return x == 9;
            }
        };
    }

    private static String material(int x, int y, int z) {
        if (z == 0 && y == 71 && x == 0) {
            return "OAK_TRAPDOOR";
        }
        if (z == 0 && y == 71 && x == 2) {
            return "IRON_TRAPDOOR";
        }
        if (z == 0 && y == 71 && x == 4) {
            return "OAK_TRAPDOOR"; // no ladder below
        }
        return y == 63 ? "STONE" : null;
    }

    @Test
    void aClosedHatchOverACapturedLadderIsClimbedForAnNpcThatOpensDoors() {
        WalkCells cells = NpcClimbables.withClosedHatches(captured(), NpcClimbablesTest::material);
        assertTrue(cells.isClimbable(0, 71, 0), "the hatch: the ladder column reaches the roof");
        assertTrue(cells.isClimbable(0, 70, 0), "the ladder itself, as captured");
        assertFalse(cells.isClimbable(2, 71, 0), "an iron hatch stays solid");
        assertFalse(cells.isClimbable(4, 71, 0), "a trapdoor with no ladder below is no hatch");
        assertFalse(cells.isClimbable(0, 72, 0), "air above the hatch is no ladder");
        assertTrue(cells.isDoor(5, 65, 0), "doors and water as captured");
        assertTrue(cells.isWater(9, 1, 1));
    }

    // ---- the route line and the waypoint chain ----

    /** {@link FakeNavWorld} that also knows where a body climbs. */
    private static final class ClimbWorld implements NavWorld, NpcClimbables.Climbs {
        final FakeNavWorld base = new FakeNavWorld();
        final Set<String> climbs = new HashSet<>();

        @Override
        public boolean climbsAt(String world, int x, int feetY, int z) {
            return climbs.contains(x + "," + feetY + "," + z);
        }

        @Override
        public boolean isLoaded(String world, int x, int z) {
            return base.isLoaded(world, x, z);
        }

        @Override
        public boolean isStandableFloor(String world, int x, int floorY, int z) {
            return base.isStandableFloor(world, x, floorY, z);
        }

        @Override
        public Set<String> regionsAt(String world, int x, int feetY, int z) {
            return base.regionsAt(world, x, feetY, z);
        }

        @Override
        public boolean blocksBody(String world, int x, int y, int z) {
            return base.blocksBody(world, x, y, z);
        }

        @Override
        public double freeHeight(String world, int x, int floorY, int z) {
            return base.freeHeight(world, x, floorY, z);
        }

        @Override
        public double surfaceY(String world, double x, int floorY, double z) {
            return base.surfaceY(world, x, floorY, z);
        }
    }

    /**
     * The tower of the {@code npc-ladder-hatch} scenario in small: ground floor 64, a ladder at (3, feet 65..71, 0),
     * the hatch at feet 72 (the roof's level), the roof's floor at 72 from x 3 on. The walk: along the ground to the
     * ladder, up it, through the hatch and onto the roof beside it. Floor points.
     */
    private static List<NavPoint> walk(ClimbWorld world) {
        world.base.floor = (x, z) -> x <= 3 ? 64 : 72;
        List<NavPoint> cells = new ArrayList<>();
        for (int x = 0; x <= 3; x++) {
            cells.add(floor(x + 0.5, 64, 0.5));
        }
        for (int feet = 66; feet <= 72; feet++) {
            world.climbs.add("3," + feet + ",0");
            cells.add(floor(3.5, feet - 1, 0.5)); // a ladder cell: floor = feet - 1, nothing to stand on
        }
        world.climbs.add("3,65,0");
        cells.add(floor(4.5, 72, 0.5)); // off the top onto the roof
        return cells;
    }

    private static List<String> feetBlocks(List<NavPoint> points) {
        List<String> out = new ArrayList<>();
        for (NavPoint p : points) {
            out.add(p.blockX() + " " + p.blockY() + " " + p.blockZ());
        }
        return out;
    }

    @Test
    void aWalkedLadderIsHandedOutCellByCellUpToTheRoof() {
        ClimbWorld world = new ClimbWorld();
        List<NavPoint> cells = walk(world);
        boolean[] walked = new boolean[cells.size()];
        java.util.Arrays.fill(walked, true);
        Stretch stretch = new RouteFollower(cells, List.of(), walked).next(world, 64, 64, 1.0, null);

        assertEquals(Kind.WAYPOINTS, stretch.kind());
        List<String> want = new ArrayList<>();
        for (NavPoint c : cells) {
            want.add(c.blockX() + " " + (c.blockY() + 1) + " " + c.blockZ());
        }
        assertEquals(want, feetBlocks(stretch.waypoints()), "every ladder cell, then the roof beside the hatch");
    }

    @Test
    void withoutClimbingTheLadderCellsWereDroppedTheBugOfIssue779() {
        ClimbWorld world = new ClimbWorld();
        List<NavPoint> cells = walk(world);
        world.climbs.clear(); // as before link H1: no cell is a climb cell
        boolean[] walked = new boolean[cells.size()];
        java.util.Arrays.fill(walked, true);
        Stretch stretch = new RouteFollower(cells, List.of(), walked).next(world, 64, 64, 1.0, null);

        List<String> got = feetBlocks(stretch.waypoints());
        assertFalse(got.contains("3 68 0"), got.toString());
    }

    @Test
    void aClimbStepIsNoGapInTheChain() {
        ClimbWorld world = new ClimbWorld();
        walk(world);
        List<NavPoint> way = new ArrayList<>();
        for (int feet = 66; feet <= 72; feet++) {
            way.add(new NavPoint(WORLD, 3.5, feet, 0.5));
        }
        way.add(new NavPoint(WORLD, 4.5, 73, 0.5));
        WaypointChain.Split split = WaypointChain.split(world, new NavPoint(WORLD, 3.5, 65, 0.5), way);
        assertNull(split.gap(), "up the ladder, then from the hatch off onto the roof: " + split);
        assertEquals(way, split.walk());
        world.climbs.clear();
        assertNotNull(WaypointChain.split(world, new NavPoint(WORLD, 3.5, 65, 0.5), way).gap(),
            "without climbing the ladder is a gap (as before link H1)");
    }

    @Test
    void aClimbStepIsOneBlockAtMostAndTouchesAClimbCell() {
        ClimbWorld world = new ClimbWorld();
        world.climbs.add("0,65,0");
        NavPoint ladder = new NavPoint(WORLD, 0.5, 65, 0.5);
        assertTrue(NpcClimbables.climbStep(world, ladder, new NavPoint(WORLD, 0.5, 66, 0.5)), "up");
        assertTrue(NpcClimbables.climbStep(world, new NavPoint(WORLD, 1.5, 65, 0.5), ladder), "onto it from beside");
        assertTrue(NpcClimbables.climbStep(world, ladder, new NavPoint(WORLD, 0.5, 64, 1.5)), "off it, a block down");
        assertFalse(NpcClimbables.climbStep(world, ladder, new NavPoint(WORLD, 1.5, 65, 1.5)), "not diagonally");
        assertFalse(NpcClimbables.climbStep(world, ladder, new NavPoint(WORLD, 0.5, 67, 0.5)), "not two up");
        assertFalse(NpcClimbables.climbStep(world, new NavPoint(WORLD, 5.5, 65, 0.5),
            new NavPoint(WORLD, 5.5, 66, 0.5)), "no climb cell");
        assertFalse(NpcClimbables.climbStep(new FakeNavWorld(), ladder, new NavPoint(WORLD, 0.5, 66, 0.5)),
            "a world that does not know climbing");
        assertFalse(NpcClimbables.climbCell(world, null));
    }
}

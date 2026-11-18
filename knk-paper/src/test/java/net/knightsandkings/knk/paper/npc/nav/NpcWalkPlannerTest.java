package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.OffRoadWalks;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.SurfaceGrid;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkBudget;
import net.knightsandkings.knk.core.roads.walk.WalkCells;
import net.knightsandkings.knk.core.roads.walk.WalkSearch;
import net.knightsandkings.knk.core.roads.walk.WalkTerrain;
import net.knightsandkings.knk.paper.navigation.walk.WalkBox;

/** Link C1 (PandiO/knk-workspace#700): the NPC's walk search on today's walk search, with a goal over every target. */
class NpcWalkPlannerTest {

    private static final String W = "world";

    /** Floor 64 everywhere in x, z -10..40; solid blocks as "x,y,z" added on top. */
    static final class Grid implements SurfaceGrid {
        final Set<String> solid = new HashSet<>();

        void wall(int x, int z0, int z1) {
            for (int z = z0; z <= z1; z++) {
                solid.add(x + ",65," + z);
                solid.add(x + ",66," + z);
                solid.add(x + ",67," + z);
            }
        }

        private boolean inside(int x, int z) {
            return x >= -10 && x <= 40 && z >= -10 && z <= 40;
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return inside(x, z) && (y == 64 || solid.contains(x + "," + y + "," + z));
        }

        @Override
        public boolean isPassable(int x, int y, int z) {
            return !isSolid(x, y, z);
        }

        @Override
        public boolean isHazard(int x, int y, int z) {
            return false;
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return 255;
        }

        @Override
        public String floorMaterial(int x, int y, int z) {
            return isSolid(x, y, z) ? "STONE" : "AIR";
        }

        @Override
        public boolean isStairOrSlab(int x, int y, int z) {
            return false;
        }
    }

    private static NavPoint floor(double x, double z) {
        return new NavPoint(W, x, 64, z);
    }

    private static OffRoadWalks.Walk search(Grid grid, NavPoint feet, List<NavPoint> targets) {
        WalkTerrain terrain = new WalkTerrain(grid, GateCells.NONE, WalkCells.NONE);
        return NpcWalkPlanner.search(terrain, CellAccess.OPEN, NpcWalkPlanner.npcProfile(MovementProfile.PLAYER, true),
            WalkBudget.DEFAULTS, feet, targets, targets.get(0), 1.5, new WalkSearch());
    }

    @Test
    void aWalkRoundAWallReachesTheTargetOnTheWalkSearchsPath() {
        Grid grid = new Grid();
        grid.wall(10, -10, 8);
        OffRoadWalks.Walk walk = search(grid, new NavPoint(W, 0.5, 65, 0.5), List.of(floor(20.5, 0.5)));

        assertTrue(walk.isFound(), walk.toString());
        assertEquals(0, walk.reached());
        assertEquals(floor(0.5, 0.5), walk.path().get(0), "the start's cell first");
        assertTrue(walk.path().stream().anyMatch(p -> p.x() > 9 && p.x() < 11 && p.z() > 8), "round the wall's end");
        assertTrue(walk.path().stream().allMatch(p -> p.y() == 64), "floor points");
        assertTrue(walk.detail().contains("expansions"), walk.detail());
    }

    @Test
    void theRoadIsJoinedAtTheTargetTheWalkReaches() {
        // the first target (the road's start) lies walled in; the walk joins at the second
        Grid grid = new Grid();
        for (int z = -2; z <= 2; z++) {
            grid.wall(18, z, z);
            grid.wall(22, z, z);
        }
        grid.wall(19, -2, -2);
        grid.wall(20, -2, -2);
        grid.wall(21, -2, -2);
        grid.wall(19, 2, 2);
        grid.wall(20, 2, 2);
        grid.wall(21, 2, 2);
        OffRoadWalks.Walk walk = search(grid, new NavPoint(W, 0.5, 65, 0.5),
            List.of(floor(20.5, 0.5), floor(20.5, 10.5), floor(20.5, 20.5)));

        assertTrue(walk.isFound(), walk.toString());
        assertEquals(1, walk.reached(), "the nearest road point it reaches");
        NavPoint end = walk.path().get(walk.path().size() - 1);
        assertTrue(Math.hypot(end.x() - 20.5, end.z() - 10.5) <= 1.5, "ends at that point: " + end);
    }

    @Test
    void noTargetReachedIsNoPathWithTheSearchsNumbers() {
        Grid grid = new Grid();
        for (int x = 15; x <= 25; x++) {
            grid.wall(x, 15, 15);
            grid.wall(x, 25, 25);
        }
        grid.wall(15, 15, 25);
        grid.wall(25, 15, 25);
        OffRoadWalks.Walk walk = search(grid, new NavPoint(W, 0.5, 65, 0.5), List.of(floor(20.5, 20.5)));

        assertFalse(walk.isFound());
        assertEquals(OffRoadWalks.Status.NO_PATH, walk.status());
        assertTrue(walk.path().isEmpty());
        assertTrue(walk.detail().contains("cap"), walk.detail());
    }

    @Test
    void theNpcProfileHasNoLaddersNoWadingAndDoorsAsItsAccessProfileSays() {
        MovementProfile npc = NpcWalkPlanner.npcProfile(MovementProfile.PLAYER, false);
        assertTrue(npc.climbables().isEmpty());
        assertFalse(npc.wades());
        assertFalse(npc.opensDoors());
        assertEquals(MovementProfile.PLAYER.maxDrop(), npc.maxDrop());
        assertEquals(MovementProfile.PLAYER.wallCost(), npc.wallCost(), 1e-9);
        assertTrue(NpcWalkPlanner.npcProfile(MovementProfile.PLAYER, true).opensDoors());
    }

    @Test
    void theBoxCoversTheFeetAndEveryTargetWithTheMargin() {
        WalkBox box = NpcWalkPlanner.boxOver(W, new NavPoint(W, 0.5, 65, 0.5),
            List.of(floor(20.5, -3.5), new NavPoint(W, 5.5, 113, 30.5)), 16, -64, 320);
        assertEquals(-16, box.minX());
        assertEquals(36, box.maxX());
        assertEquals(-20, box.minZ());
        assertEquals(46, box.maxZ());
        assertEquals(64 - 16, box.minY());
        assertEquals(113 + 1 + 16, box.maxY(), "the goal upstairs is inside, with headroom and margin");
    }

    @Test
    void anyOfArrivesNearAnyTargetIn3D() {
        var goal = NpcWalkPlanner.anyOf(List.of(floor(0.5, 0.5), new NavPoint(W, 10.5, 80, 0.5)), 1.5);
        assertTrue(goal.reached(1.5, 64, 0.5));
        assertTrue(goal.reached(10.5, 80, 1.5));
        assertFalse(goal.reached(10.5, 64, 0.5), "under the second target, a floor too low");
    }

    @Test
    void cellsABodyCannotStandInAreFoundOnAPathButNotItsStart() {
        // an open trapdoor's panel at feet 65 on x 3 (link C1, the keep tower's decorative door)
        NpcWalkPlanner.BodyCheck body = (world, x, feetY, z) -> x == 3 && feetY == 65 || x == 0;
        List<NavPoint> path = List.of(floor(0.5, 0.5), floor(1.5, 0.5), floor(2.5, 0.5), floor(3.5, 0.5),
            floor(4.5, 0.5));
        List<int[]> blocked = NpcWalkPlanner.bodyBlocked(body, path);
        assertEquals(1, blocked.size(), "the start's own cell is not judged");
        assertEquals(List.of(3, 65, 0), List.of(blocked.get(0)[0], blocked.get(0)[1], blocked.get(0)[2]));
    }

    @Test
    void aClosedCellIsWalkedRound() {
        // what a search again after the body check does: the cell closed, the search goes round it
        Grid grid = new Grid();
        grid.wall(5, -10, -1);
        grid.wall(5, 1, 10);
        WalkTerrain terrain = new WalkTerrain(grid, GateCells.NONE, WalkCells.NONE);
        CellAccess closed = (x, feetY, z) -> x == 5 && z == 0 ? CellAccess.BLOCKED : 0.0;
        OffRoadWalks.Walk walk = NpcWalkPlanner.search(terrain, closed, NpcWalkPlanner.npcProfile(MovementProfile.PLAYER,
            true), WalkBudget.DEFAULTS, new NavPoint(W, 0.5, 65, 0.5), List.of(floor(10.5, 0.5)), floor(10.5, 0.5), 1.5,
            new WalkSearch());
        assertTrue(walk.isFound(), walk.toString());
        assertTrue(walk.path().stream().noneMatch(p -> p.blockX() == 5 && p.blockZ() == 0), "not through the gap");
    }
}

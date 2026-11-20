package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.landmarks.LandmarkWeights;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.OffRoadWalks;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.SurfaceGrid;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkBudget;
import net.knightsandkings.knk.core.roads.route.RegionShape;
import net.knightsandkings.knk.core.roads.walk.WalkCellWeights;
import net.knightsandkings.knk.core.roads.walk.WalkCells;
import net.knightsandkings.knk.core.roads.walk.WalkPathfinder;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkResult;
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

        /** Materials other than stone, as "x,y,z" (link H1: a hatch). */
        final java.util.Map<String, String> materials = new java.util.HashMap<>();

        @Override
        public String floorMaterial(int x, int y, int z) {
            return materials.getOrDefault(x + "," + y + "," + z, isSolid(x, y, z) ? "STONE" : "AIR");
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
    void theNpcProfileClimbsLaddersDoesNotWadeAndOpensDoorsAsItsAccessProfileSays() {
        MovementProfile npc = NpcWalkPlanner.npcProfile(MovementProfile.PLAYER, false);
        // link H1 (#779): ladders as the walk settings' (C1 had none; the search read them from the capture anyway)
        assertEquals(MovementProfile.PLAYER.climbables(), npc.climbables());
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

    // ==================== off-road phase 5.2 (PandiO/knk-workspace#709): no landmark weights for NPCs ====================

    /** Runs the NPC search and keeps the request it sent and the result it got. */
    private static final class Recording implements WalkPathfinder {
        final List<WalkRequest> requests = new ArrayList<>();
        final List<WalkResult> results = new ArrayList<>();

        @Override
        public WalkResult find(WalkRequest request) {
            requests.add(request);
            WalkResult result = new WalkSearch().find(request);
            results.add(result);
            return result;
        }
    }

    private static OffRoadWalks.Walk npcWalk(Grid grid, Recording pathfinder) {
        WalkTerrain terrain = new WalkTerrain(grid, GateCells.NONE, WalkCells.NONE);
        List<NavPoint> targets = List.of(floor(20.5, 0.5));
        return NpcWalkPlanner.search(terrain, CellAccess.OPEN, NpcWalkPlanner.npcProfile(MovementProfile.PLAYER, true),
            WalkBudget.DEFAULTS, new NavPoint(W, 0.5, 65, 0.5), targets, targets.get(0), 1.5, pathfinder);
    }

    @Test
    void anNpcWalkLegHasTheSameCostAndPathWithLandmarkWeightsOnOrOff() {
        // navigation.landmarks on: a landmark of preference 4.0 lies across the NPC's straight way. Its weights reach
        // a player's walk leg only through NavigationService.forMode in the preferred mode; NpcWalkPlanner builds its
        // own requests, which carry none.
        LandmarkWeights hall = LandmarkWeights.of(List.of(new LandmarkWeights.Area(1,
            new LandmarkWeights.RegionArea(RegionShape.cuboid(5, 60, -3, 15, 80, 3)), 4.0)), 4.0);
        Recording off = new Recording();
        Recording on = new Recording();

        OffRoadWalks.Walk withoutLandmarks = npcWalk(new Grid(), off);
        OffRoadWalks.Walk withLandmarks = npcWalk(new Grid(), on);

        assertTrue(withLandmarks.isFound(), withLandmarks.toString());
        assertTrue(on.requests.stream().allMatch(r -> r.weights() == WalkCellWeights.NONE), "no weights on an NPC request");
        assertEquals(withoutLandmarks.path(), withLandmarks.path(), "the same path");
        assertEquals(off.results.get(0).path().orElseThrow().cost(), on.results.get(0).path().orElseThrow().cost(), 0.0,
            "the same cost");

        // the weight would change the walk: the same request as a weighted player leg goes round the hall
        WalkRequest request = on.requests.get(0);
        WalkResult weighted = new WalkSearch().find(request.withWeights(hall.within(request.reachBox())));
        assertTrue(weighted.isFound());
        assertTrue(weighted.path().orElseThrow().cost() > off.results.get(0).path().orElseThrow().cost(),
            "weighted, the walk costs more (it goes round)");
        assertFalse(weighted.path().orElseThrow().length() == off.results.get(0).path().orElseThrow().length(),
            "and is longer");
    }
    // ==================== link H1 (PandiO/knk-workspace#779): a ladder and a roof hatch ====================

    /**
     * A tower in small: a ladder at (5, 65..71, 0), a roof at y 72 over x 3..8, z -2..2 with a closed oak hatch at
     * (5, 72, 0) right above the ladder. The roof is 8 blocks above the ground: the ladder is the only way up.
     */
    private static WalkTerrain tower(Grid grid) {
        for (int x = 3; x <= 8; x++) {
            for (int z = -2; z <= 2; z++) {
                grid.solid.add(x + ",72," + z);
            }
        }
        grid.materials.put("5,72,0", "OAK_TRAPDOOR");
        WalkCells ladder = new WalkCells() {
            @Override
            public boolean isDoor(int x, int y, int z) {
                return false;
            }

            @Override
            public boolean isClimbable(int x, int y, int z) {
                return x == 5 && z == 0 && y >= 65 && y <= 71; // as captured: the closed hatch is no climbable
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return false;
            }
        };
        return new WalkTerrain(grid, GateCells.NONE, ladder);
    }

    private static OffRoadWalks.Walk toTheRoof(WalkTerrain terrain, boolean opensDoors) {
        List<NavPoint> roof = List.of(new NavPoint(W, 7.5, 72, 1.5));
        return NpcWalkPlanner.search(NpcWalkPlanner.forNpc(terrain, opensDoors), CellAccess.OPEN,
            NpcWalkPlanner.npcProfile(MovementProfile.PLAYER, opensDoors), WalkBudget.DEFAULTS,
            new NavPoint(W, 0.5, 65, 0.5), roof, roof.get(0), 1.5, new WalkSearch());
    }

    @Test
    void anNpcThatOpensDoorsClimbsThroughAClosedHatch() {
        OffRoadWalks.Walk walk = toTheRoof(tower(new Grid()), true);
        assertTrue(walk.isFound(), walk.toString());
        assertTrue(walk.path().stream().anyMatch(p -> p.blockX() == 5 && p.blockZ() == 0 && p.blockY() == 71),
            "through the hatch (its cell's floor is the ladder's top, 71): " + walk.path());
    }

    @Test
    void forAnNpcWithoutOpenDoorsAClosedHatchStaysSolid() {
        OffRoadWalks.Walk walk = toTheRoof(tower(new Grid()), false);
        assertFalse(walk.isFound(), "the closed hatch blocks: " + walk);
        WalkTerrain terrain = tower(new Grid());
        assertTrue(NpcWalkPlanner.forNpc(terrain, false) == terrain, "the capture unchanged");
    }

    @Test
    void theBodyCheckIsMadeForEachLegsClimbablesAndDoorRule() {
        NpcWalkPlanner.BodyCheck leg = NpcWalkPlanner.BodyCheck.BUKKIT.forLeg(java.util.Set.of("LADDER", "VINE"), true);
        assertEquals(new NpcWalkPlanner.BodyCheck.Bukkit(java.util.Set.of("LADDER", "VINE"), true), leg);
        NpcWalkPlanner.BodyCheck fake = (world, x, feetY, z) -> false;
        assertTrue(fake.forLeg(java.util.Set.of(), true) == fake, "a test's check stays as it is");
    }
}

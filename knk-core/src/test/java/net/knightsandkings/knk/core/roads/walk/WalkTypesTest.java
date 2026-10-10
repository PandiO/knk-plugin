package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.GridFixture;
import net.knightsandkings.knk.core.util.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The small value types and ports of {@code roads/walk} and the walk view of {@link WalkGrid}. */
class WalkTypesTest {

    // ===== MovementProfile =====

    @Test
    void thePlayerProfileCarriesTheDecidedDefaults() {
        MovementProfile p = MovementProfile.PLAYER;

        assertEquals(2, p.headroom());
        assertEquals(3, p.maxDrop());
        assertEquals(10.0, p.dropPenalty());
        assertEquals(3.0, p.doorCost());
        assertEquals(3.0, p.waterFactor());
        assertEquals(2.0, p.climbCost());
        assertTrue(p.opensDoors());
        assertTrue(p.wades());
        assertTrue(p.isClimbable("LADDER"));
        assertFalse(p.isClimbable("VINE"));
    }

    @Test
    void profileVariantsKeepTheRestAndNormaliseClimbables() {
        MovementProfile p = MovementProfile.PLAYER.withDrops(2, 4.0).withClimbables(Set.of(" ladder ", "VINE"));

        assertEquals(2, p.maxDrop());
        assertEquals(4.0, p.dropPenalty());
        assertEquals(Set.of("LADDER", "VINE"), p.climbables());
        assertEquals(MovementProfile.PLAYER.doorCost(), p.doorCost());
    }

    @Test
    void invalidProfilesAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new MovementProfile(0, 3, 10, 1, true, 3, true, 3, Set.of(), 2));
        assertThrows(IllegalArgumentException.class, () -> MovementProfile.PLAYER.withDrops(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> MovementProfile.PLAYER.withDrops(3, Double.NaN));
        assertThrows(IllegalArgumentException.class, () ->
            new MovementProfile(2, 3, 10, 1, true, 3, true, 0.5, Set.of(), 2));
        assertThrows(IllegalArgumentException.class, () ->
            new MovementProfile(2, 3, 10, 1, true, 3, true, 3, Set.of(), 0.5));
    }

    // ===== CellAccess =====

    @Test
    void cellAccessCombinesBlockedWinsCostsAdd() {
        CellAccess gate = (x, y, z) -> x == 1 ? CellAccess.BLOCKED : 0.0;
        CellAccess toll = (x, y, z) -> 2.5;
        CellAccess all = CellAccess.all(toll, gate);

        assertEquals(2.5, all.extraCost(0, 65, 0));
        assertEquals(CellAccess.BLOCKED, all.extraCost(1, 65, 0));
        assertFalse(all.allows(1, 65, 0));
        assertTrue(all.allows(0, 65, 0));
        assertEquals(Optional.of("blocked"), all.denyReason(1, 65, 0));
        assertEquals(Optional.empty(), all.denyReason(0, 65, 0));
        assertEquals(0.0, CellAccess.OPEN.extraCost(5, 5, 5));
    }

    @Test
    void aNegativeOrNaNAccessAnswerCountsAsBlocked() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 0, 64, GridFixture.GRASS);
        for (double bad : new double[] {-1.0, Double.NaN}) {
            WalkResult result = new WalkSearch().find(f.request(0, 64, 0, 4, 64, 0)
                .withAccess((x, y, z) -> x == 2 ? bad : 0.0));
            assertEquals(WalkResult.Status.NO_PATH, result.status(), "answer " + bad);
        }
    }

    // ===== budget, goal, request =====

    @Test
    void theLengthCapIsTheFactorOrTheDetourAllowanceUpToTheMaximum() {
        assertEquals(58.0, WalkBudget.DEFAULTS.lengthCap(10), 1e-9, "short legs: straight + 48");
        assertEquals(75.5, WalkBudget.DEFAULTS.lengthCap(27.5), 1e-9, "the 2026-10-07 Merchant Square leg");
        assertEquals(96.0, WalkBudget.DEFAULTS.lengthCap(48), 1e-9, "legs up to 48 blocks: as before KNG-75");
        assertEquals(140.0, WalkBudget.DEFAULTS.lengthCap(80), 1e-9, "KNG-75 step 2a: the factor, no longer cut at 96");
        assertEquals(144.0, WalkBudget.DEFAULTS.lengthCap(96), 1e-9, "never above max-length");
        WalkBudget factorOnly = new WalkBudget(20_000, 1.75, 96, 0, 2, 3);
        assertEquals(17.5, factorOnly.lengthCap(10), 1e-9, "allowance 0: the factor alone, as before");
        assertEquals(84.0, new WalkBudget(20_000, 1.75, 200, 10, 2, 3).lengthCap(48), 1e-9, "long legs: the factor");
        assertThrows(IllegalArgumentException.class, () -> new WalkBudget(0, 1.75, 96, 48, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> new WalkBudget(10, 0.5, 96, 48, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> new WalkBudget(10, 1.75, 96, -1, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> new WalkBudget(10, 1.75, 96, Double.NaN, 2, 3));
    }

    @Test
    void aPointGoalIsReachedWithinItsDistance() {
        WalkGoal goal = WalkGoal.within(10.5, 64, 10.5, 2.0);

        assertTrue(goal.reached(10.5, 64, 10.5));
        assertTrue(goal.reached(12.5, 64, 10.5));
        assertFalse(goal.reached(12.6, 64, 10.5));
        assertFalse(goal.reached(10.5, 67, 10.5));
    }

    @Test
    void theStraightDistanceIsMeasuredBetweenFloorPositions() {
        WalkFixture f = new WalkFixture();
        WalkRequest r = WalkRequest.toPoint(f.terrain(), 0.5, 65.0, 0.5, 3.5, 68, 4.5, 1);

        assertEquals(Math.sqrt(9 + 16 + 16), r.straightDistance(), 1e-9);
        assertThrows(IllegalArgumentException.class, () ->
            WalkRequest.toPoint(f.terrain(), Double.NaN, 65, 0, 1, 64, 1, 1));
    }

    // ===== WalkCells =====

    @Test
    void walkCellsFromMaterialsKnowDoorsLaddersAndWater() {
        WalkFixture f = new WalkFixture()
            .block(0, 64, 0, WalkFixture.OAK_DOOR).block(1, 64, 0, WalkFixture.IRON_DOOR)
            .block(2, 64, 0, WalkFixture.OAK_FENCE_GATE).block(3, 64, 0, WalkFixture.LADDER)
            .block(4, 64, 0, GridFixture.WATER);
        WalkCells cells = f.terrain().cells();

        assertTrue(cells.isDoor(0, 64, 0));
        assertFalse(cells.isDoor(1, 64, 0), "iron doors need redstone");
        assertTrue(cells.isDoor(2, 64, 0));
        assertTrue(cells.isClimbable(3, 64, 0));
        assertFalse(cells.isClimbable(4, 64, 0));
        assertTrue(cells.isWater(4, 64, 0));
        assertFalse(WalkCells.NONE.isDoor(0, 64, 0) || WalkCells.NONE.isClimbable(3, 64, 0)
            || WalkCells.NONE.isWater(4, 64, 0));
    }

    // ===== WalkGrid, walk view =====

    @Test
    void theWalkGridAcceptsAnyWalkFloorNotOnlyRoads() {
        WalkFixture f = new WalkFixture().layer(64, "gXf");
        WalkGrid grid = WalkSearch.gridFor(f.terrain(), MovementProfile.PLAYER);

        assertTrue(grid.isCell(0, 64, 0), "grass is a walk floor");
        assertTrue(grid.isCell(1, 64, 0), "plain stone is a walk floor");
        assertFalse(grid.isCell(2, 64, 0), "a fence is never a floor");
        assertTrue(grid.neighbour(BlockKey.pack(0, 64, 0), WalkGrid.E).isPresent());
    }

    @Test
    void doorAndLadderBlocksArePassableInTheWalkViewOnly() {
        WalkFixture f = new WalkFixture().layer(64, "gg").layer(65, "D.").block(1, 65, 0, WalkFixture.LADDER);
        WalkGrid walk = WalkSearch.gridFor(f.terrain(), MovementProfile.PLAYER);
        WalkGrid roads = new WalkGrid(f.grid, f.grid, 2, WalkGrid.FloorTest.ANY);

        assertTrue(walk.isPassable(0, 65, 0));
        assertTrue(walk.isCell(0, 64, 0), "the floor in a doorway");
        assertFalse(roads.isPassable(0, 65, 0), "the builder's view: a door is solid");
        assertFalse(roads.isCell(0, 64, 0));
        assertFalse(walk.isCell(0, 65, 0), "a door is never a floor");
        assertFalse(walk.isCell(1, 65, 0), "a ladder is never a floor");
    }

    @Test
    void theHeadroomComesFromTheProfile() {
        WalkFixture f = new WalkFixture().layer(64, "g").block(0, 67, 0, GridFixture.STONE);
        MovementProfile tall = new MovementProfile(3, 3, 10, 1, true, 3, true, 3, Set.of("LADDER"), 2);

        assertTrue(WalkSearch.gridFor(f.terrain(), MovementProfile.PLAYER).isCell(0, 64, 0));
        assertFalse(WalkSearch.gridFor(f.terrain(), tall).isCell(0, 64, 0), "a 3-high mover needs 3 blocks");
    }
}

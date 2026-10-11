package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.GridFixture;
import net.knightsandkings.knk.core.util.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The KNG-51 §12 core fixtures: one test per row of the test plan. */
class WalkSearchTest {

    private static final String G = GridFixture.GRASS;

    private static WalkPath found(WalkResult result) {
        assertEquals(WalkResult.Status.FOUND, result.status(), result.toString());
        return result.path().orElseThrow();
    }

    /** The path's cells as "x,y,z" (L prefix for ladder cells), for readable assertions. */
    private static List<String> cells(WalkPath path) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            long k = path.cell(i);
            out.add((path.isLadder(i) ? "L" : "") + BlockKey.x(k) + "," + BlockKey.y(k) + "," + BlockKey.z(k));
        }
        return out;
    }

    private static boolean visits(WalkPath path, int x, int y, int z) {
        for (int i = 0; i < path.size(); i++) {
            if (path.cell(i) == BlockKey.pack(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private static boolean visitsColumn(WalkPath path, int x, int z) {
        for (int i = 0; i < path.size(); i++) {
            if (BlockKey.x(path.cell(i)) == x && BlockKey.z(path.cell(i)) == z) {
                return true;
            }
        }
        return false;
    }

    // ===== flat ground, alleys, walls =====

    @Test
    void aFlatFieldGivesTheStraightPath() {
        WalkFixture f = new WalkFixture().floor(0, 0, 9, 2, 64, G);
        WalkPath path = found(f.walk(0, 64, 1, 9, 64, 1));

        assertEquals(10, path.size());
        assertEquals(9.0, path.length(), 1e-9);
        for (int i = 0; i < path.size(); i++) {
            assertEquals(BlockKey.pack(i, 64, 1), path.cell(i), cells(path).toString());
        }
        double[] first = path.points().get(0);
        assertEquals(0.5, first[0], 1e-9);
        assertEquals(64.0, first[1], 1e-9, "points are floor positions");
        assertEquals(1.5, first[2], 1e-9);
    }

    @Test
    void openGroundUsesDiagonals() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 4, 64, G);
        WalkPath path = found(f.walk(0, 64, 0, 4, 64, 4));

        assertEquals(5, path.size());
        assertEquals(4 * Math.sqrt(2), path.length(), 1e-9);
    }

    @Test
    void anAlleyWithAOneBlockJogIsFollowed() {
        WalkFixture f = new WalkFixture().layer(64,
            "#######",
            "ggg####",
            "##gggg#",
            "#####gg",
            "#######");
        WalkPath path = found(f.walk(0, 64, 1, 6, 64, 3));

        assertTrue(visits(path, 2, 64, 1) || visits(path, 2, 64, 2), cells(path).toString());
        for (int i = 0; i < path.size(); i++) {
            assertEquals(64, BlockKey.y(path.cell(i)), "never on top of the alley walls: " + cells(path));
        }
    }

    @Test
    void aWallIsWalkedAroundNotThrough() {
        // a 4-high wall across z = 2 with its only opening at x = 6; stairs lead nowhere useful
        WalkFixture f = new WalkFixture().floor(0, 0, 6, 4, 64, G).layer(65,
            ".......",
            ".......",
            "######.",
            ".......",
            ".......");
        WalkPath path = found(f.walk(0, 64, 0, 0, 64, 4));

        assertTrue(visits(path, 6, 64, 2), "through the opening: " + cells(path));
        for (int x = 0; x < 6; x++) {
            assertFalse(visitsColumn(path, x, 2), "never through the wall: " + cells(path));
        }
        assertTrue(path.length() > 4.0);
    }

    @Test
    void aOneWideGapIsUsed() {
        WalkFixture f = new WalkFixture().floor(0, 0, 8, 4, 64, G).layer(65,
            ".........",
            ".........",
            "####.####",
            ".........",
            ".........");
        WalkPath path = found(f.walk(0, 64, 0, 8, 64, 4));

        assertTrue(visits(path, 4, 64, 2), cells(path).toString());
    }

    @Test
    void aStepUpOntoAFullBlockCostsAJumpAndOntoAStairDoesNot() {
        WalkFixture full = new WalkFixture().floor(0, 0, 1, 0, 64, G).floor(2, 0, 4, 0, 65, GridFixture.STONE);
        WalkPath jumped = found(full.walk(0, 64, 0, 4, 65, 0));
        assertEquals(5.0, jumped.length(), 1e-9, "4 across, 1 up");
        assertEquals(4.0 + 1.0, jumped.cost(), 1e-9, "+1 for the jump");

        WalkFixture stair = new WalkFixture().floor(0, 0, 1, 0, 64, G).floor(3, 0, 4, 0, 65, GridFixture.STONE)
            .block(2, 65, 0, GridFixture.STAIRS);
        assertEquals(4.0, found(stair.walk(0, 64, 0, 4, 65, 0)).cost(), 1e-9, "a stair needs no jump");

        WalkFixture lowCeiling = new WalkFixture().floor(0, 0, 1, 0, 64, G).floor(2, 0, 4, 0, 65, GridFixture.STONE)
            .block(1, 67, 0, GridFixture.STONE);
        assertEquals(WalkResult.Status.NO_PATH, lowCeiling.walk(0, 64, 0, 4, 65, 0).status(),
            "no room to jump under a low ceiling");
    }

    // ===== drops =====

    @Test
    void aDropOfThreeIsAllowedWhenItIsTheOnlyWay() {
        WalkFixture f = new WalkFixture().floor(0, 0, 2, 0, 67, G).floor(3, 0, 6, 0, 64, G);
        WalkPath path = found(f.walk(0, 67, 0, 6, 64, 0));

        assertTrue(visits(path, 2, 67, 0) && visits(path, 3, 64, 0), cells(path).toString());
        assertEquals(6 + 3, path.length(), 1e-9, "the drop counts its height");
        assertTrue(path.cost() >= 30.0, "+10 per block dropped: " + path.cost());
    }

    @Test
    void aDropOfFourIsRefused() {
        WalkFixture f = new WalkFixture().floor(0, 0, 2, 0, 68, G).floor(3, 0, 6, 0, 64, G);

        assertEquals(WalkResult.Status.NO_PATH, f.walk(0, 68, 0, 6, 64, 0).status());
    }

    @Test
    void aDropIsAvoidedWhenAStairwayExists() {
        // ledge at 67 over ground at 64; a staircase down at the far end (z = 2)
        WalkFixture f = new WalkFixture()
            .floor(0, 0, 2, 2, 67, G)
            .floor(3, 0, 8, 2, 64, G)
            .block(3, 66, 2, GridFixture.STAIRS).block(4, 65, 2, GridFixture.STAIRS);
        f.grid.column(3, 2, 64, 65, GridFixture.STONE);
        f.grid.block(4, 64, 2, GridFixture.STONE);
        WalkPath path = found(f.walk(0, 67, 0, 6, 64, 0));

        assertTrue(visits(path, 3, 66, 2), "down the stairs: " + cells(path));
        assertTrue(path.cost() < 30.0, "no drop penalty: " + path.cost());
    }

    @Test
    void aDropNeedsAClearFallColumn() {
        // the drop column at x = 3 has a block sticking out at head height
        WalkFixture f = new WalkFixture().floor(0, 0, 2, 0, 67, G).floor(3, 0, 6, 0, 64, G)
            .block(3, 69, 0, GridFixture.STONE);

        assertEquals(WalkResult.Status.NO_PATH, f.walk(0, 67, 0, 6, 64, 0).status());
    }

    @Test
    void noDropsWhenTheProfileForbidsThem() {
        WalkFixture f = new WalkFixture().floor(0, 0, 2, 0, 67, G).floor(3, 0, 6, 0, 64, G);
        WalkRequest request = f.request(0, 67, 0, 6, 64, 0).withProfile(MovementProfile.PLAYER.withDrops(1, 10));

        assertEquals(WalkResult.Status.NO_PATH, new WalkSearch().find(request).status());
    }

    // ===== never a floor =====

    @Test
    void fencesWallsAndPanesAreNeverAFloor() {
        for (char barrier : new char[] {'f', 'w', 'p'}) {
            String row = "" + barrier + barrier + barrier + barrier + barrier;
            WalkFixture f = new WalkFixture().floor(0, 0, 4, 4, 64, G).layer(65,
                ".....", ".....", row, ".....", ".....");

            assertEquals(WalkResult.Status.NO_PATH, f.walk(2, 64, 0, 2, 64, 4).status(),
                "nobody jumps onto or over a " + barrier);
        }
    }

    // ===== doors =====

    private static WalkFixture doorway(char door) {
        return new WalkFixture().floor(0, 0, 4, 4, 64, G).layer(65,
            ".....", ".....", "##" + door + "##", ".....", ".....");
    }

    @Test
    void anAllowedDoorIsWalkedThroughAtAPenalty() {
        WalkPath path = found(doorway('D').walk(2, 64, 0, 2, 64, 4));

        assertTrue(visits(path, 2, 64, 2), cells(path).toString());
        assertEquals(4.0, path.length(), 1e-9);
        assertEquals(4.0 + 3.0, path.cost(), 1e-9, "door +3");
    }

    @Test
    void aFenceGateCountsAsADoor() {
        WalkPath path = found(doorway('F').walk(2, 64, 0, 2, 64, 4));

        assertTrue(visits(path, 2, 64, 2), cells(path).toString());
        assertEquals(7.0, path.cost(), 1e-9);
    }

    @Test
    void aDeniedDoorBlocks() {
        WalkFixture f = doorway('D');
        CellAccess deniedDoor = (x, feetY, z) ->
            WalkFixture.OAK_DOOR.equals(f.grid.material(x, feetY, z)) ? CellAccess.BLOCKED : 0.0;
        WalkResult result = new WalkSearch().find(f.request(2, 64, 0, 2, 64, 4).withAccess(deniedDoor));

        assertEquals(WalkResult.Status.NO_PATH, result.status());
    }

    @Test
    void anIronDoorNeverOpens() {
        assertEquals(WalkResult.Status.NO_PATH, doorway('I').walk(2, 64, 0, 2, 64, 4).status());
    }

    @Test
    void aMoverThatOpensNoDoorsIsBlockedByOne() {
        MovementProfile noDoors = new MovementProfile(2, 3, 10, 1, false, 3, true, 3,
            MovementProfile.PLAYER.climbables(), 2);
        WalkResult result = new WalkSearch().find(doorway('D').request(2, 64, 0, 2, 64, 4).withProfile(noDoors));

        assertEquals(WalkResult.Status.NO_PATH, result.status());
    }

    @Test
    void aDiagonalDoesNotSlipPastADoorOrADeniedCorner() {
        // from just in front of the door, diagonally behind it: straight through the doorway, no corner cut
        WalkPath path = found(doorway('D').walk(1, 64, 1, 3, 64, 3));
        assertTrue(visits(path, 2, 64, 1) && visits(path, 2, 64, 2) && visits(path, 2, 64, 3), cells(path).toString());
        assertEquals(4.0, path.length(), 1e-9);
        assertEquals(4.0 + 3.0, path.cost(), 1e-9);

        // a denied corner cell is not a way round
        WalkFixture open = new WalkFixture().floor(0, 0, 1, 1, 64, G);
        CellAccess denyCorners = (x, feetY, z) -> (x == 1 && z == 0) || (x == 0 && z == 1) ? CellAccess.BLOCKED : 0.0;
        WalkResult result = new WalkSearch().find(open.request(0, 64, 0, 1, 64, 1).withAccess(denyCorners));
        assertEquals(WalkResult.Status.NO_PATH, result.status(), result.toString());
    }

    // ===== ladders =====

    /** Ground at 64 (x 0..2), a wall at x = 3 whose top is 68, a ladder on its face at x = 2, y 65..68. */
    private static WalkFixture ladderWall(boolean topExit) {
        WalkFixture f = new WalkFixture().floor(0, 0, 2, 0, 64, G)
            .column(3, 0, 64, 68, GridFixture.STONE)
            .floor(4, 0, 5, 0, 68, G)
            .column(2, 0, 65, 68, WalkFixture.LADDER);
        if (!topExit) {
            f.column(3, 0, 69, 71, GridFixture.STONE);
        }
        return f;
    }

    @Test
    void aLadderIsClimbedUpAndStepsOffAtTheTop() {
        WalkPath path = found(ladderWall(true).walk(0, 64, 0, 5, 68, 0));

        List<String> cells = cells(path);
        assertTrue(cells.containsAll(List.of("L2,65,0", "L2,66,0", "L2,67,0", "L2,68,0", "3,68,0")), cells.toString());
        int top = cells.indexOf("L2,68,0");
        assertEquals("3,68,0", cells.get(top + 1), "off the top onto the wall: " + cells);
        assertEquals(67.0, path.points().get(top)[1], 1e-9, "a ladder cell's floor position is feet - 1");
    }

    @Test
    void aLadderIsClimbedDown() {
        WalkPath path = found(ladderWall(true).walk(5, 68, 0, 0, 64, 0));

        List<String> cells = cells(path);
        assertTrue(cells.containsAll(List.of("L2,68,0", "L2,65,0")), cells.toString());
        assertTrue(path.cost() < 30.0, "climbed, not dropped: " + path.cost());
    }

    @Test
    void aLadderWithoutATopExitLeadsNowhere() {
        WalkFixture f = ladderWall(false).floor(4, 0, 5, 0, 71, G);

        assertEquals(WalkResult.Status.NO_PATH, f.walk(0, 64, 0, 5, 71, 0).status());
    }

    @Test
    void aLadderReachesALedgeBesideIt() {
        // the ledge is a floor block at 67 west of the ladder's top, over open air
        WalkFixture f = new WalkFixture().floor(0, 0, 2, 1, 64, G)
            .column(3, 0, 64, 70, GridFixture.STONE)
            .column(2, 0, 65, 68, WalkFixture.LADDER)
            .block(2, 67, 1, G);
        WalkPath path = found(f.walk(0, 64, 0, 2, 67, 1));

        assertTrue(path.isLadder(path.size() - 2), "reached from the ladder: " + cells(path));
        assertEquals("2,67,1", cells(path).get(path.size() - 1));
    }

    // ===== access =====

    @Test
    void aClosedGateTheMoverMayNotPassForcesADetourOrNoPath() {
        // wall across z = 2 with a gate at x = 2 and an opening at x = 6
        WalkFixture f = new WalkFixture().floor(0, 0, 6, 4, 64, G).layer(65,
            ".......", ".......", "##.###.", ".......", ".......");
        f.grid.gate(9, 2, 64, 2, 3);
        CellAccess gateShut = (x, feetY, z) -> f.grid.doorAt(x, feetY, z).isPresent() ? CellAccess.BLOCKED : 0.0;

        WalkPath open = found(new WalkSearch().find(f.request(2, 64, 0, 2, 64, 4)));
        assertTrue(visits(open, 2, 64, 2), "an open gate is walked through: " + cells(open));

        WalkPath detour = found(new WalkSearch().find(f.request(2, 64, 0, 2, 64, 4).withAccess(gateShut)));
        assertTrue(visits(detour, 6, 64, 2), "around through the opening: " + cells(detour));
        assertFalse(visits(detour, 2, 64, 2));

        f.grid.layer(6, 65, 2, "#");
        assertEquals(WalkResult.Status.NO_PATH,
            new WalkSearch().find(f.request(2, 64, 0, 2, 64, 4).withAccess(gateShut)).status());
    }

    @Test
    void deniedRegionCellsAreWalkedAround() {
        WalkFixture f = new WalkFixture().floor(0, 0, 10, 6, 64, G);
        CellAccess region = (x, feetY, z) -> x >= 3 && x <= 7 && z <= 4 ? CellAccess.BLOCKED : 0.0;
        WalkPath path = found(new WalkSearch().find(f.request(0, 64, 2, 10, 64, 2).withAccess(region)));

        for (int i = 0; i < path.size(); i++) {
            int x = BlockKey.x(path.cell(i));
            int z = BlockKey.z(path.cell(i));
            assertFalse(x >= 3 && x <= 7 && z <= 4, "inside the denied region: " + cells(path));
        }
    }

    @Test
    void aFiniteAccessCostIsPaid() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 0, 64, G);
        WalkPath path = found(new WalkSearch().find(f.request(0, 64, 0, 4, 64, 0)
            .withAccess((x, feetY, z) -> x == 2 ? 5.0 : 0.0)));

        assertEquals(4.0 + 5.0, path.cost(), 1e-9);
    }

    // ===== outcomes =====

    @Test
    void anUnreachableGoalIsNoPath() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 0, 64, G).floor(7, 0, 9, 0, 64, G);
        WalkResult result = f.walk(0, 64, 0, 9, 64, 0);

        assertEquals(WalkResult.Status.NO_PATH, result.status());
        assertEquals("target unreachable", result.reason());
    }

    @Test
    void anExhaustedExpansionBudgetIsFallback() {
        WalkFixture f = new WalkFixture().floor(0, 0, 30, 0, 64, G);
        WalkRequest request = f.request(0, 64, 0, 30, 64, 0).withBudget(new WalkBudget(5, 1.75, 96, 48, 2, 3));
        WalkResult result = new WalkSearch().find(request);

        assertEquals(WalkResult.Status.FALLBACK, result.status());
        assertEquals("expansion budget", result.reason());
        assertEquals(5, result.expansions());
    }

    @Test
    void aDetourLongerThanTheLengthCapIsFallback() {
        // the straight distance is 4, the only way round is ~16 > 1.75 × 4 (no detour allowance)
        WalkFixture f = new WalkFixture().floor(0, 0, 8, 4, 64, G).layer(65,
            ".........", ".........", "########.", ".........", ".........");
        WalkResult result = new WalkSearch().find(f.request(0, 64, 0, 0, 64, 4)
            .withBudget(new WalkBudget(20_000, 1.75, 96, 0, 2, 3)));

        assertEquals(WalkResult.Status.FALLBACK, result.status(), result.toString());
        assertEquals("length cap", result.reason());

        WalkRequest generous = f.request(0, 64, 0, 0, 64, 4).withBudget(new WalkBudget(20_000, 10, 96, 0, 2, 3));
        assertTrue(new WalkSearch().find(generous).isFound());
        assertTrue(new WalkSearch().find(f.request(0, 64, 0, 0, 64, 4).withBudget(WalkBudget.DEFAULTS)).isFound(),
            "the default detour allowance covers it");
    }

    @Test
    void aTargetBehindABuildingIsFoundWithTheDefaultBudget() {
        // Finding N2 (live test 2026-10-07): /navigate Merchant Square from 27.5 blocks away, a building in
        // between; the walkable way round is 67.7 blocks, the old cap min(96, 1.75 × 27.5) = 48 gave FALLBACK.
        // Here: 26 blocks straight, a wall across the field with a gap at the far end, ~62 blocks round.
        String[] rows = new String[27];
        java.util.Arrays.fill(rows, "...............................");
        rows[13] = "############################...";
        WalkFixture f = new WalkFixture().floor(0, 0, 30, 26, 64, G).layer(65, rows);
        WalkRequest request = f.request(1, 64, 0, 1, 64, 26).withBudget(WalkBudget.DEFAULTS);
        WalkResult result = new WalkSearch().find(request);

        assertTrue(result.isFound(), result.toString());
        double length = result.path().orElseThrow().length();
        assertTrue(length > 1.75 * request.straightDistance(), "longer than the old cap: " + length);
        assertTrue(length <= WalkBudget.DEFAULTS.lengthCap(request.straightDistance()) + 1e-9);
        assertEquals(WalkResult.Status.FALLBACK, new WalkSearch().find(request.withBudget(
            new WalkBudget(20_000, 1.75, 96, 0, 2, 3))).status(), "the old cap");
    }

    /**
     * A tower 12 high whose only way down is a long stair (KNG-108, the Keep Tower Roof of 2026-10-09): a roof at 76
     * over (0, 0); a 1-wide walkway east along z = 0 that steps down a block every 5 blocks, a landing at (30, 1),
     * and back west along z = 2 down to the ground at 64. Nothing below the walkways: no drop shortcuts it.
     */
    private static WalkFixture tallTower() {
        WalkFixture f = new WalkFixture().block(0, 76, 0, GridFixture.STONE).block(0, 64, 2, GridFixture.STONE)
            .block(30, 70, 1, GridFixture.STONE);
        for (int x = 1; x <= 30; x++) {
            f.block(x, 75 - (x - 1) / 5, 0, GridFixture.STONE);
            f.block(x, 64 + (x - 1) / 5, 2, GridFixture.STONE);
        }
        return f;
    }

    @Test
    void theClimbAllowanceLetsAStairDownATallTowerThroughTheLengthCap() {
        WalkBudget noClimb = new WalkBudget(20_000, 1.75, 144, 0, 0, 2, 3);
        WalkBudget climb = new WalkBudget(20_000, 1.75, 144, 0, 5, 2, 3);
        WalkRequest down = tallTower().request(0, 76, 0, 0, 64, 2);
        assertEquals(12.0, down.heightDifference(), 1e-9);

        assertEquals(WalkResult.Status.FALLBACK, new WalkSearch().find(down.withBudget(noClimb)).status(),
            "the factor alone (1.75 × 12.2 = 21.3) cuts the stair off");
        WalkPath path = found(new WalkSearch().find(down.withBudget(climb)));
        assertTrue(path.length() > noClimb.lengthCap(down.straightDistance(), down.heightDifference()),
            "longer than the cap without the allowance: " + path.length());
        assertTrue(path.length() <= climb.lengthCap(down.straightDistance(), down.heightDifference()) + 1e-9);
        assertTrue(visits(path, 30, 70, 1), "round the landing: " + cells(path));

        WalkRequest up = tallTower().request(0, 64, 2, 0, 76, 0);
        assertEquals(12.0, up.heightDifference(), 1e-9, "up counts as down");
        assertTrue(new WalkSearch().find(up.withBudget(climb)).isFound(), "the last leg up to a roof");
        assertTrue(new WalkSearch().find(down.withBudget(WalkBudget.DEFAULTS)).isFound(), "the shipped defaults");
    }

    @Test
    void theWallCostRoundsAnOuterCornerABlockWide() {
        // live test 2026-10-08 (N7): the trail hugged corners so tightly it seemed to stop. A building in the
        // north-west corner of a field; around its south-east corner from the south side to the east side.
        String[] rows = new String[13];
        java.util.Arrays.fill(rows, ".............");
        for (int z = 0; z <= 5; z++) {
            rows[z] = "######.......";
        }
        WalkFixture f = new WalkFixture().floor(0, 0, 12, 12, 64, G).layer(65, rows);
        WalkRequest request = f.request(2, 64, 9, 9, 64, 2);

        WalkPath hugging = found(new WalkSearch().find(request));
        WalkPath wide = found(new WalkSearch().find(request.withProfile(MovementProfile.PLAYER)));

        assertTrue(touchesBuilding(hugging), "without the wall cost the path brushes the corner: " + hugging);
        assertFalse(touchesBuilding(wide), "with it the path keeps a block away: " + wide);
        assertTrue(wide.length() <= hugging.length() + 2.5, "for a small detour: " + wide.length() + " vs " + hugging.length());
    }

    /** Whether a path cell has the building (x 0..5, z 0..5) among its 8 neighbours. */
    private static boolean touchesBuilding(WalkPath path) {
        for (int i = 0; i < path.size(); i++) {
            long c = path.cell(i);
            if (BlockKey.x(c) <= 6 && BlockKey.z(c) <= 6) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aBudgetRunOutGivesNoPathButAPartialOne() {
        WalkFixture f = new WalkFixture().floor(0, 0, 30, 0, 64, G);
        WalkResult result = new WalkSearch().find(
            f.request(0, 64, 0, 30, 64, 0).withBudget(new WalkBudget(10, 1.75, 96, 48, 2, 3)));

        assertTrue(result.path().isEmpty(), "never a path that does not arrive");
        WalkPath partial = result.partialPath().orElseThrow();
        assertEquals(BlockKey.pack(0, 64, 0), partial.cell(0));
        assertEquals(10, BlockKey.x(partial.cell(partial.size() - 1)), "the closest of the 11 cells closed");
    }

    @Test
    void anUnreachableTargetGivesThePartialPathToTheClosestReachableCell() {
        // §11-5 (2026-10-07): a walled-in target - follow the way to the wall, the rest is a straight line
        WalkFixture f = new WalkFixture().floor(0, 0, 12, 6, 64, G).layer(65,
            ".............", ".............", "........#####", "........#...#", "........#...#", "........#####", ".............");
        WalkResult result = f.walk(0, 64, 0, 10, 64, 4);

        assertEquals(WalkResult.Status.NO_PATH, result.status(), result.toString());
        assertTrue(result.path().isEmpty());
        WalkPath partial = result.partialPath().orElseThrow();
        long end = partial.cell(partial.size() - 1);
        double endDistance = Math.hypot(BlockKey.x(end) + 0.5 - 10.5, BlockKey.z(end) + 0.5 - 4.5);
        assertEquals(2.0, endDistance, 1e-9, "next to the wall, beside the target: " + partial);
    }

    @Test
    void noPartialPathWhenNoCellGetsClearlyCloser() {
        WalkFixture f = new WalkFixture().floor(0, 0, 1, 0, 64, G).floor(5, 0, 6, 0, 64, G);
        WalkResult result = f.walk(0, 64, 0, 5, 64, 0);

        assertEquals(WalkResult.Status.NO_PATH, result.status());
        assertTrue(result.partialPath().isEmpty(), "one block closer is no partial path: " + result);
        assertTrue(new WalkSearch().find(f.request(0, 64, 0, 5, 64, 0)).path().isEmpty());
    }

    // ===== snapping =====

    @Test
    void theStartSnapsToTheNearestCellWithinTwoBlocks() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 0, 64, G);
        WalkSearch search = new WalkSearch();
        // feet two blocks above the floor (mid-jump), near the edge of the block
        WalkRequest midAir = WalkRequest.toPoint(f.terrain(), 0.9, 67.0, 0.5, 4.5, 64, 0.5, 0.25);
        WalkPath path = found(search.find(midAir));
        assertEquals(BlockKey.pack(0, 64, 0), path.cell(0));

        WalkRequest tooHigh = WalkRequest.toPoint(f.terrain(), 0.5, 69.5, 0.5, 4.5, 64, 0.5, 0.25);
        WalkResult result = search.find(tooHigh);
        assertEquals(WalkResult.Status.NO_PATH, result.status());
        assertEquals("no walkable cell near the start", result.reason());
    }

    @Test
    void aTargetNeedsAWalkCellWithinThreeBlocks() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 0, 64, G);
        WalkSearch search = new WalkSearch();

        WalkRequest near = WalkRequest.toPoint(f.terrain(), 0.5, 65, 0.5, 4.5, 66, 0.5, 2.5);
        assertTrue(search.find(near).isFound(), "a target 2 above the ground snaps");

        WalkRequest far = WalkRequest.toPoint(f.terrain(), 0.5, 65, 0.5, 4.5, 69, 0.5, 2.5);
        WalkResult result = search.find(far);
        assertEquals(WalkResult.Status.NO_PATH, result.status());
        assertEquals("no walkable cell near the target", result.reason());
    }

    @Test
    void theStartIsAlreadyTheGoal() {
        WalkFixture f = new WalkFixture().floor(0, 0, 1, 0, 64, G);
        WalkPath path = found(f.walk(0, 64, 0, 0, 64, 0));

        assertEquals(1, path.size());
        assertEquals(0.0, path.length(), 1e-9);
    }

    @Test
    void aRegionGoalEndsAtTheFirstCellInside() {
        WalkFixture f = new WalkFixture().floor(0, 0, 9, 0, 64, G);
        WalkRequest request = f.request(0, 64, 0, 9, 64, 0).withGoal((x, floorY, z) -> x >= 6);
        WalkPath path = found(new WalkSearch().find(request));

        assertEquals(BlockKey.pack(6, 64, 0), path.cell(path.size() - 1));
    }

    // ===== levels, water =====

    @Test
    void aTwoLevelStackedPlazaIsClimbedByItsStairs() {
        // lower plaza at 64, an upper deck at 69 over x 0..4; stairs from (6,64..) up to the deck at its east end
        WalkFixture f = new WalkFixture().floor(0, 0, 10, 2, 64, G).floor(0, 0, 4, 2, 69, GridFixture.STONE_BRICKS);
        for (int i = 0; i < 4; i++) {
            int x = 9 - i;
            int y = 65 + i;
            f.block(x, y, 2, GridFixture.STAIRS);
            f.column(x, 2, 65, y - 1, GridFixture.STONE);
        }
        f.floor(5, 2, 5, 2, 69, GridFixture.STONE_BRICKS);
        f.column(5, 2, 65, 68, GridFixture.STONE);
        WalkPath path = found(f.walk(2, 64, 0, 2, 69, 0));

        assertTrue(visits(path, 9, 65, 2) && visits(path, 6, 68, 2), "up the stairs: " + cells(path));
        WalkPath under = found(f.walk(0, 64, 1, 4, 64, 1));
        assertTrue(under.size() <= 5, "the lower level under the deck is still walkable: " + cells(under));
    }

    @Test
    void shallowWaterIsWadedAtThreeTimesTheCostAndAvoidedWhenCheap() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 2, 64, G)
            .block(1, 65, 0, GridFixture.WATER).block(2, 65, 0, GridFixture.WATER).block(3, 65, 0, GridFixture.WATER);
        WalkPath avoided = found(f.walk(0, 64, 0, 4, 64, 0));
        assertFalse(visits(avoided, 2, 64, 0), "around the puddle: " + cells(avoided));

        WalkFixture channel = new WalkFixture().floor(0, 0, 4, 0, 64, G)
            .block(2, 65, 0, GridFixture.WATER);
        WalkPath waded = found(channel.walk(0, 64, 0, 4, 64, 0));
        assertEquals(4.0 + 2.0, waded.cost(), 1e-9, "the wading move costs ×3");
    }

    @Test
    void waterOverTheHeadIsNotWalkable() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 0, 64, G)
            .column(2, 0, 65, 66, GridFixture.WATER);

        assertEquals(WalkResult.Status.NO_PATH, f.walk(0, 64, 0, 4, 64, 0).status());
    }

    @Test
    void hazardsAreNeverWalked() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 0, 64, G).block(2, 64, 0, GridFixture.LAVA);

        assertEquals(WalkResult.Status.NO_PATH, f.walk(0, 64, 0, 4, 64, 0).status());
    }

    @Test
    void searchesAreDeterministic() {
        WalkFixture f = new WalkFixture().floor(0, 0, 9, 9, 64, G);
        WalkPath a = found(f.walk(0, 64, 0, 9, 64, 5));
        WalkPath b = found(f.walk(0, 64, 0, 9, 64, 5));

        assertEquals(cells(a), cells(b));
    }
}

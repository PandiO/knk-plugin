package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.GridFixture;
import net.knightsandkings.knk.core.util.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clearance cost of the walk search (off-road navigation phase 6.1, PandiO/knk-workspace#710, DESIGN § 7.1.4,
 * § 13 A16): a cost per block of clearance a cell lacks to {@code wall-clearance}, and a clearance cost of 0 (the
 * default, and {@code navigation.guidance-v2: false}) giving exactly today's search.
 */
class WalkClearanceTest {

    private static final String G = GridFixture.GRASS;
    private static final MovementProfile CLEARANCE = MovementProfile.PLAYER.withClearance(5, 0.8);

    // ===== the clearance of a cell =====

    @Test
    void aCellBesideAWallLacksWhatTheWallTakes() {
        WalkFixture f = new WalkFixture().floor(0, 0, 8, 8, 64, G).layer(65,
            ".........",
            ".........",
            ".........",
            ".........",
            "....#....");
        WalkClearance clearance = clearanceOf(f, 0.8);

        assertEquals(0.5, clearance.of(4, 64, 3), 1e-9, "a wall square to the cell: half a block");
        assertEquals(Math.sqrt(0.5), clearance.of(3, 64, 3), 1e-9, "a wall at the corner: half a diagonal");
        assertEquals(0.8, clearance.of(4, 64, 2), 1e-9, "a block further: the cap");
        assertEquals(0.3, clearance.lack(4, 64, 3, 0.8), 1e-9);
        assertEquals(0.0, clearance.lack(4, 64, 2, 0.8), 1e-9);
        assertEquals(1.5, clearanceOf(f, 2.0).of(4, 64, 2), 1e-9, "a larger cap looks further");
    }

    @Test
    void aDropAndTheEdgeOfTheCaptureAreObstaclesButAStepIsNot() {
        WalkFixture f = new WalkFixture().floor(0, 0, 8, 8, 64, G).floor(5, 0, 8, 8, 65, G)
            .block(2, 64, 4, GridFixture.AIR).block(2, 62, 4, G);
        WalkClearance clearance = clearanceOf(f, 0.8);

        assertEquals(0.8, clearance.of(4, 64, 1), 1e-9, "a step up to the next column is walkable");
        assertEquals(0.5, clearance.of(2, 64, 3), 1e-9, "a hole of two blocks beside: a drop");
        assertEquals(0.5, clearance.of(0, 64, 4), 1e-9, "beyond the capture: nothing to stand on");
    }

    @Test
    void aCellTheAccessBlocksIsAnObstacle() {
        WalkFixture f = new WalkFixture().floor(0, 0, 8, 8, 64, G);
        CellAccess denied = (x, feetY, z) -> x == 5 ? CellAccess.BLOCKED : 0.0;
        WalkTerrainNavWorld world = new WalkTerrainNavWorld(f.terrain(), MovementProfile.PLAYER, denied);
        WalkClearance clearance = new WalkClearance(world::isStanding, 0.8);

        assertEquals(0.5, clearance.of(4, 64, 4), 1e-9, "a region the mover may not enter keeps the path off");
    }

    @Test
    void theCapMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new WalkClearance((x, y, z) -> true, 0));
        assertThrows(IllegalArgumentException.class, () -> MovementProfile.PLAYER.withClearance(-1, 0.8));
        assertThrows(IllegalArgumentException.class, () -> MovementProfile.PLAYER.withClearance(5, 0.3));
        assertThrows(IllegalArgumentException.class, () -> MovementProfile.PLAYER.withClearance(5, 2.5));
    }

    // ===== the search =====

    @Test
    void eachCellEnteredCostsTheClearanceItLacksTimesTheCost() {
        // a one-wide strip: every cell has an obstacle square to it on both sides (clearance 0.5, lacking 0.3)
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 0, 64, G);
        WalkPath path = found(new WalkSearch().find(f.request(0, 64, 0, 4, 64, 0).withProfile(CLEARANCE)));

        assertEquals(4.0, path.length(), 1e-9);
        assertEquals(4.0 + 4 * 5 * 0.3, path.cost(), 1e-9, "the start cell is not entered");
        WalkPath geometry = found(new WalkSearch().find(f.request(0, 64, 0, 4, 64, 0)));
        assertEquals(4.0, geometry.cost(), 1e-9, "without wall or clearance cost: the length");
    }

    @Test
    void theClearanceCostReplacesTheWallCost() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 0, 64, G);
        MovementProfile both = MovementProfile.PLAYER.withWallCost(7).withClearance(5, 0.8);
        assertEquals(4.0 + 4 * 1.5, found(new WalkSearch().find(f.request(0, 64, 0, 4, 64, 0).withProfile(both))).cost(),
            1e-9, "no wall cost on top");
    }

    @Test
    void theClearanceCostKeepsAPathOffTheFaceOfABuilding() {
        String[] rows = new String[13];
        Arrays.fill(rows, ".............");
        for (int z = 0; z <= 5; z++) {
            rows[z] = "######.......";
        }
        WalkFixture f = new WalkFixture().floor(0, 0, 12, 12, 64, G).layer(65, rows);
        WalkRequest request = f.request(2, 64, 9, 9, 64, 2);
        WalkPath bare = found(new WalkSearch().find(request));
        WalkPath wide = found(new WalkSearch().find(request.withProfile(CLEARANCE)));

        assertTrue(besideBuilding(bare) > 0, "without a cost the path runs along the building: " + bare);
        assertEquals(0, besideBuilding(wide), "with it no cell is square to the building's face: " + wide);
    }

    @Test
    void aClearanceCostOfZeroIsTodaysSearchExactly() {
        // A16: the kill switch. Every scene below is searched with today's profile (wall cost 1.0), with a clearance
        // cost of 0 at any wall-clearance, and through the constructor callers from before phase 6.1 use.
        MovementProfile legacy = new MovementProfile(2, 3, 10.0, 1.0, true, 3.0, true, 3.0, Set.of("LADDER"), 2.0, 1.0);
        assertEquals(MovementProfile.PLAYER, legacy, "the shipped player has no clearance cost");
        assertEquals(0.0, MovementProfile.PLAYER.clearanceCost());
        assertFalse(MovementProfile.PLAYER.usesClearance());
        for (WalkRequest request : scenes()) {
            WalkResult today = new WalkSearch(() -> 0L).find(request.withProfile(MovementProfile.PLAYER));
            for (MovementProfile off : List.of(legacy, MovementProfile.PLAYER.withClearance(0, 0.8),
                MovementProfile.PLAYER.withClearance(0, 2.0))) {
                WalkResult same = new WalkSearch(() -> 0L).find(request.withProfile(off));
                assertEquals(today.toString(), same.toString());
                assertEquals(today.expansions(), same.expansions());
                assertEquals(today.stats(), same.stats());
                assertEquals(today.path().map(WalkPath::cost), same.path().map(WalkPath::cost));
            }
        }
    }

    // ===== helpers =====

    /** The scenes of the identity check: open field, a corner, a wall with a gap, the spiral stair, a ladder. */
    private static List<WalkRequest> scenes() {
        String[] corner = new String[13];
        Arrays.fill(corner, ".............");
        for (int z = 0; z <= 5; z++) {
            corner[z] = "######.......";
        }
        String[] gap = new String[13];
        Arrays.fill(gap, ".............");
        gap[6] = "##########...";
        WalkFixture ladder = new WalkFixture().floor(0, 0, 2, 0, 64, G).column(3, 0, 65, 68, GridFixture.STONE)
            .column(2, 0, 65, 68, WalkFixture.LADDER);
        return List.of(
            new WalkFixture().floor(0, 0, 12, 12, 64, G).request(0, 64, 0, 12, 64, 7),
            new WalkFixture().floor(0, 0, 12, 12, 64, G).layer(65, corner).request(2, 64, 9, 9, 64, 2),
            new WalkFixture().floor(0, 0, 12, 12, 64, G).layer(65, gap).request(1, 64, 1, 1, 64, 11),
            SpiralStair.fixture().request(5, SpiralStair.BASE, 3, 5, SpiralStair.BASE + 2 * SpiralStair.RISE, 3),
            ladder.request(0, 64, 0, 3, 68, 0));
    }

    private static WalkClearance clearanceOf(WalkFixture f, double cap) {
        WalkTerrainNavWorld world = new WalkTerrainNavWorld(f.terrain(), MovementProfile.PLAYER, CellAccess.OPEN);
        return new WalkClearance(world::isStanding, cap);
    }

    private static WalkPath found(WalkResult result) {
        assertTrue(result.isFound(), result.toString());
        return result.path().orElseThrow();
    }

    /** Cells of the path with the building (x 0..5, z 0..5) square to them. */
    private static int besideBuilding(WalkPath path) {
        int n = 0;
        for (int i = 0; i < path.size(); i++) {
            long c = path.cell(i);
            int x = BlockKey.x(c);
            int z = BlockKey.z(c);
            if (x <= 5 && z == 6 || x == 6 && z <= 5) {
                n++;
            }
        }
        return n;
    }
}

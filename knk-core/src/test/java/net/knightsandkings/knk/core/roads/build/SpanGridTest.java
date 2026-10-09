package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.util.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.OptionalLong;

import static net.knightsandkings.knk.core.roads.build.SpanGrid.E;
import static net.knightsandkings.knk.core.roads.build.SpanGrid.N;
import static net.knightsandkings.knk.core.roads.build.SpanGrid.NE;
import static net.knightsandkings.knk.core.roads.build.SpanGrid.NO_LINK;
import static net.knightsandkings.knk.core.roads.build.SpanGrid.NW;
import static net.knightsandkings.knk.core.roads.build.SpanGrid.S;
import static net.knightsandkings.knk.core.roads.build.SpanGrid.SE;
import static net.knightsandkings.knk.core.roads.build.SpanGrid.SW;
import static net.knightsandkings.knk.core.roads.build.SpanGrid.W;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpanGridTest {

    private static long key(int x, int y, int z) {
        return BlockKey.pack(x, y, z);
    }

    @Test
    void directionTableIsClockwiseFromNorthAndOppositesMatch() {
        assertEquals(0, SpanGrid.DX[N]);
        assertEquals(-1, SpanGrid.DZ[N]);
        assertEquals(1, SpanGrid.DX[E]);
        assertEquals(0, SpanGrid.DZ[E]);
        assertEquals(1, SpanGrid.DZ[S]);
        assertEquals(-1, SpanGrid.DX[W]);
        for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
            int o = SpanGrid.opposite(d);
            assertEquals(-SpanGrid.DX[d], SpanGrid.DX[o]);
            assertEquals(-SpanGrid.DZ[d], SpanGrid.DZ[o]);
            assertEquals(d, SpanGrid.opposite(o));
        }
        assertTrue(SpanGrid.isDiagonal(NE));
        assertTrue(SpanGrid.isDiagonal(SW));
        assertFalse(SpanGrid.isDiagonal(E));
    }

    @Test
    void aSpanIsARoadFloorWithTwoPassableBlocksAbove() {
        GridFixture f = new GridFixture().layer(64, "SgX.");
        SpanGrid grid = f.spanGrid();

        assertTrue(grid.isSpan(0, 64, 0), "stone bricks under air");
        assertFalse(grid.isSpan(1, 64, 0), "grass is not a road material");
        assertFalse(grid.isSpan(2, 64, 0), "plain stone is not a road material");
        assertFalse(grid.isSpan(3, 64, 0), "air is not a floor");
        assertFalse(grid.isSpan(0, 65, 0), "the air above the floor is not a span");
    }

    @Test
    void headroomMustBePassableAndHazardFree() {
        GridFixture f = new GridFixture().layer(64, "SSSSS")
            .block(1, 65, 0, GridFixture.STONE)   // block right above the floor
            .block(2, 66, 0, GridFixture.STONE)   // block two above the floor
            .block(3, 67, 0, GridFixture.STONE)   // block three above: fine for standing
            .block(4, 65, 0, "FIRE");             // passable but a hazard
        SpanGrid grid = f.spanGrid();

        assertTrue(grid.isSpan(0, 64, 0));
        assertFalse(grid.isSpan(1, 64, 0));
        assertFalse(grid.isSpan(2, 64, 0));
        assertTrue(grid.isSpan(3, 64, 0));
        assertFalse(grid.isSpan(4, 64, 0));
    }

    @Test
    void hazardFloorsAreNotSpansAndGateDoorBlocksCountAsHeadroom() {
        GridFixture f = new GridFixture().layer(64, "SS").block(0, 64, 0, "MAGMA_BLOCK")
            .gate(7, 1, 64, 0, 2);
        SpanGrid grid = f.spanGrid();

        assertFalse(grid.isSpan(0, 64, 0), "magma is a hazard (and not a road material)");
        assertTrue(grid.isSpan(1, 64, 0), "the closed iron-bar door above stone bricks is passable headroom");
        assertEquals(OptionalInt.of(7), grid.gateDoor(key(1, 64, 0)));
        assertEquals(OptionalInt.empty(), grid.gateDoor(key(0, 64, 0)));
    }

    @Test
    void closedFootprintTagsEvenWhenTheDoorIsOpen() {
        GridFixture f = new GridFixture().layer(64, "S").gateCell(3, 0, 65, 0);
        SpanGrid grid = f.spanGrid();

        assertTrue(grid.isSpan(0, 64, 0));
        assertEquals(OptionalInt.of(3), grid.gateDoor(key(0, 64, 0)));
    }

    @Test
    void overlaysAreLookedThroughAndAreNeverFloorsThemselves() {
        GridFixture f = new GridFixture().layer(64, "SS").layer(65, ".s");
        SpanGrid grid = f.spanGrid();

        assertTrue(grid.isSpan(1, 64, 0), "stone bricks under a snow layer");
        assertFalse(grid.isSpan(1, 65, 0), "the snow layer is not a floor");
        assertEquals(GridFixture.STONE_BRICKS, grid.floorMaterial(key(1, 64, 0)));
        assertEquals(0, grid.neighbourDy(key(0, 64, 0), E), "level link under the snow");
    }

    @Test
    void worldHeightLimitsApply() {
        GridFixture f = new GridFixture().heightRange(64, 67).layer(64, "S").layer(63, "S").block(0, 65, 5, GridFixture.STONE_BRICKS);
        SpanGrid grid = f.spanGrid();

        assertTrue(grid.isSpan(0, 64, 0), "64 + 2 headroom = 66 < maxY 67");
        assertFalse(grid.isSpan(0, 63, 0), "below minY");
        assertFalse(grid.isSpan(0, 65, 5), "65 + 2 = 67 is not below maxY (exclusive)");
    }

    @Test
    void ambiguityComesFromTheProfiles() {
        GridFixture f = new GridFixture().layer(64, "Sc");
        SpanGrid grid = f.spanGrid();

        assertFalse(grid.isAmbiguous(key(0, 64, 0)));
        assertTrue(grid.isAmbiguous(key(1, 64, 0)));
    }

    @Test
    void levelNeighboursInAllEightDirections() {
        GridFixture f = new GridFixture().layer(64, "SSS", "SSS", "SSS");
        SpanGrid grid = f.spanGrid();
        long centre = key(1, 64, 1);

        for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
            assertEquals(0, grid.neighbourDy(centre, d), "direction " + d);
            OptionalLong nb = grid.neighbour(centre, d);
            assertTrue(nb.isPresent());
            assertEquals(1 + SpanGrid.DX[d], BlockKey.x(nb.getAsLong()));
            assertEquals(1 + SpanGrid.DZ[d], BlockKey.z(nb.getAsLong()));
        }
        long corner = key(0, 64, 0);
        assertEquals(NO_LINK, grid.neighbourDy(corner, N));
        assertEquals(NO_LINK, grid.neighbourDy(corner, W));
        assertEquals(NO_LINK, grid.neighbourDy(corner, NW));
        assertEquals(0, grid.neighbourDy(corner, SE));
        assertTrue(grid.neighbour(corner, NW).isEmpty());
    }

    @Test
    void stepUpNeedsRoomToJumpOverTheLowerSpan() {
        // x=0 at y=64, x=1 at y=65: a one-block step. Open sky → allowed.
        SpanGrid open = new GridFixture().layer(64, "S.").layer(65, ".S").spanGrid();
        assertEquals(1, open.neighbourDy(key(0, 64, 0), E));
        assertEquals(-1, open.neighbourDy(key(1, 65, 0), W), "symmetric");

        // Same step under a ceiling three above the lower floor: no room to jump.
        SpanGrid low = new GridFixture().layer(64, "S.").layer(65, ".S").block(0, 67, 0, GridFixture.STONE).spanGrid();
        assertTrue(low.isSpan(0, 64, 0), "still a span: 65 and 66 are free");
        assertEquals(NO_LINK, low.neighbourDy(key(0, 64, 0), E));
        assertEquals(NO_LINK, low.neighbourDy(key(1, 65, 0), W));
    }

    @Test
    void stairsAndSlabsAreSteppedOntoWithoutJumping() {
        SpanGrid stairs = new GridFixture().layer(64, "S.").layer(65, "./").block(0, 67, 0, GridFixture.STONE).spanGrid();
        assertEquals(1, stairs.neighbourDy(key(0, 64, 0), E));
        assertEquals(-1, stairs.neighbourDy(key(1, 65, 0), W));

        SpanGrid slab = new GridFixture().layer(64, "S.").layer(65, "._").block(0, 67, 0, GridFixture.STONE).spanGrid();
        assertEquals(1, slab.neighbourDy(key(0, 64, 0), E));

        // Stepping *down* onto a stair from a full block still needs the jump room above the stair.
        SpanGrid stairBelow = new GridFixture().layer(64, "/.").layer(65, ".S").block(0, 67, 0, GridFixture.STONE).spanGrid();
        assertEquals(NO_LINK, stairBelow.neighbourDy(key(1, 65, 0), W), "the upper span is a full block");
    }

    @Test
    void atMostOneNeighbourPerDirectionAndOnlyWithinOneBlockOfHeight() {
        // Column x=1 has floors at 64 and 66: 64 is not a span (66 blocks its headroom), 66 is.
        SpanGrid grid = new GridFixture().layer(64, "SS").block(1, 66, 0, GridFixture.STONE_BRICKS).spanGrid();
        assertFalse(grid.isSpan(1, 64, 0));
        assertTrue(grid.isSpan(1, 66, 0));
        assertEquals(NO_LINK, grid.neighbourDy(key(0, 64, 0), E), "two blocks up is not a link");

        // A tunnel three below a road never links to it.
        SpanGrid stacked = new GridFixture().layer(64, "S").layer(67, "S").spanGrid();
        assertTrue(stacked.isSpan(0, 64, 0));
        assertTrue(stacked.isSpan(0, 67, 0));
    }

    @Test
    void diagonalLinksNeedAnOrthogonalCorner() {
        // Two roads touching only at a corner: (0,0) and (1,1) are spans, (1,0) and (0,1) are not.
        SpanGrid corner = new GridFixture().layer(64, "S.", ".S").spanGrid();
        assertEquals(NO_LINK, corner.neighbourDy(key(0, 64, 0), SE));
        assertEquals(NO_LINK, corner.neighbourDy(key(1, 64, 1), NW));

        // With one orthogonal span the diagonal joins.
        SpanGrid l = new GridFixture().layer(64, "SS", ".S").spanGrid();
        assertEquals(0, l.neighbourDy(key(0, 64, 0), SE));
        assertEquals(0, l.neighbourDy(key(1, 64, 1), NW));
        assertEquals(NO_LINK, l.neighbourDy(key(1, 64, 0), SW), "nothing at (0,1)");
    }

    @Test
    void diagonalCornerPathMustEndOnTheSameSpan() {
        // (0,0) at 64, corner (1,0) at 65 via a stair, (1,1) at 66: the L-path climbs two blocks, so the
        // diagonal (dy limited to ±1 from 64) finds nothing at 63..65 in column (1,1) and stays unlinked.
        SpanGrid grid = new GridFixture().layer(64, "S.", "..").layer(65, "./", "..").layer(66, "..", ".S").spanGrid();
        assertEquals(1, grid.neighbourDy(key(0, 64, 0), E));
        assertEquals(1, grid.neighbourDy(key(1, 65, 0), S));
        assertEquals(NO_LINK, grid.neighbourDy(key(0, 64, 0), SE));
    }

    @Test
    void linksAreSymmetricOnAMixedHeightPatch() {
        GridFixture f = new GridFixture()
            .layer(64, "SSS..", "SS...", "S....")
            .layer(65, "...S.", "../S.", "./SS.")
            .layer(66, "....S", "....S", "....S");
        SpanGrid grid = f.spanGrid();

        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 3; z++) {
                for (int y = 64; y <= 66; y++) {
                    if (!grid.isSpan(x, y, z)) {
                        continue;
                    }
                    long a = key(x, y, z);
                    for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
                        int dy = grid.neighbourDy(a, d);
                        if (dy == NO_LINK) {
                            continue;
                        }
                        long b = BlockKey.neighbour(a, SpanGrid.DX[d], dy, SpanGrid.DZ[d]);
                        assertTrue(grid.isSpan(b));
                        assertEquals(-dy, grid.neighbourDy(b, SpanGrid.opposite(d)),
                            "link " + x + "," + y + "," + z + " dir " + d + " must be symmetric");
                    }
                }
            }
        }
    }

    @Test
    void wallsBlockStandingAndHeadroom() {
        SpanGrid grid = new GridFixture().layer(64, "S#S").spanGrid();
        assertTrue(grid.isSpan(0, 64, 0));
        assertFalse(grid.isSpan(1, 64, 0), "a wall column is stone, not road");
        assertFalse(grid.isSpan(1, 65, 0));
        assertEquals(NO_LINK, grid.neighbourDy(key(0, 64, 0), E));
    }

    @Test
    void gatesAreReadThroughTheGateCellsPortNotTheSurfaceGrid() {
        GridFixture f = new GridFixture().layer(64, "S").gate(1, 0, 64, 0, 2);
        assertFalse(f.isPassable(0, 65, 0), "the surface grid says iron bars are solid");
        SpanGrid withGates = new SpanGrid(f, GridFixture.profiles(), f);
        SpanGrid withoutGates = new SpanGrid(f, GridFixture.profiles(), GateCells.NONE);
        assertTrue(withGates.isSpan(0, 64, 0));
        assertFalse(withoutGates.isSpan(0, 64, 0));
    }
}

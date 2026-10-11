package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.util.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadMaskTest {

    /** Every span of the fixture between the given bounds, at any of the listed heights. */
    static long[] allSpans(SpanGrid grid, int maxX, int maxZ, int... ys) {
        List<Long> keys = new ArrayList<>();
        for (int x = 0; x <= maxX; x++) {
            for (int z = 0; z <= maxZ; z++) {
                for (int y : ys) {
                    if (grid.isSpan(x, y, z)) {
                        keys.add(BlockKey.pack(x, y, z));
                    }
                }
            }
        }
        return keys.stream().mapToLong(Long::longValue).toArray();
    }

    @Test
    void indexesSpansAndTheirNeighboursWithinTheMask() {
        SpanGrid grid = new GridFixture().layer(64, "SSS", "SSS", "SSS").spanGrid();
        RoadMask mask = RoadMask.of(grid, allSpans(grid, 2, 2, 64));

        assertEquals(9, mask.size());
        int centre = mask.indexOf(1, 64, 1);
        assertTrue(centre >= 0);
        assertEquals(8, mask.neighbourCount(centre));
        assertFalse(mask.isBorder(centre));
        int corner = mask.indexOf(0, 64, 0);
        assertEquals(3, mask.neighbourCount(corner));
        assertTrue(mask.isBorder(corner));
        assertEquals(mask.indexOf(1, 64, 0), mask.neighbour(corner, SpanGrid.E));
        assertEquals(RoadMask.NONE, mask.neighbour(corner, SpanGrid.N));
        assertEquals(RoadMask.NONE, mask.indexOf(5, 64, 5));
        assertEquals(1, mask.levelCount());
        assertEquals(GridFixture.STONE_BRICKS, mask.floor(centre));
        assertEquals(RoadMask.NONE, mask.gateDoor(centre));
    }

    @Test
    void spansOutsideTheMaskReadAsNonRoad() {
        SpanGrid grid = new GridFixture().layer(64, "SSS").spanGrid();
        RoadMask mask = RoadMask.of(grid, new long[] {BlockKey.pack(0, 64, 0), BlockKey.pack(1, 64, 0)});

        assertEquals(2, mask.size());
        int middle = mask.indexOf(1, 64, 0);
        assertEquals(RoadMask.NONE, mask.neighbour(middle, SpanGrid.E), "(2,64,0) is a span of the world but not of the mask");
        assertTrue(mask.isBorder(middle));
    }

    @Test
    void duplicatesAreDroppedAndKeysAreSorted() {
        SpanGrid grid = new GridFixture().layer(64, "SS").spanGrid();
        long a = BlockKey.pack(0, 64, 0);
        long b = BlockKey.pack(1, 64, 0);
        RoadMask mask = RoadMask.of(grid, new long[] {b, a, b, a});

        assertEquals(2, mask.size());
        assertTrue(mask.key(0) < mask.key(1));
    }

    @Test
    void neighbourTableIsSymmetric() {
        SpanGrid grid = new GridFixture()
            .layer(64, "SSS..", "SS...", "S....")
            .layer(65, "...S.", "../S.", "./SS.")
            .spanGrid();
        RoadMask mask = RoadMask.of(grid, allSpans(grid, 4, 2, 64, 65));

        for (int i = 0; i < mask.size(); i++) {
            for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
                int nb = mask.neighbour(i, d);
                if (nb != RoadMask.NONE) {
                    assertEquals(i, mask.neighbour(nb, SpanGrid.opposite(d)));
                }
            }
        }
    }

    @Test
    void subsetFiltersTheTableWithoutAskingTheWorld() {
        SpanGrid grid = new GridFixture().layer(64, "SSS").spanGrid();
        RoadMask mask = RoadMask.of(grid, allSpans(grid, 2, 0, 64));
        boolean[] keep = new boolean[3];
        keep[mask.indexOf(0, 64, 0)] = true;
        keep[mask.indexOf(1, 64, 0)] = true;
        RoadMask sub = mask.subset(keep);

        assertEquals(2, sub.size());
        int middle = sub.indexOf(1, 64, 0);
        assertEquals(RoadMask.NONE, sub.neighbour(middle, SpanGrid.E));
        assertEquals(sub.indexOf(0, 64, 0), sub.neighbour(middle, SpanGrid.W));
        assertEquals(RoadMask.NONE, sub.indexOf(2, 64, 0));

        boolean[] all = {true, true, true};
        assertSame(mask, mask.subset(all), "keeping everything returns the same mask");
        assertThrows(IllegalArgumentException.class, () -> mask.subset(new boolean[2]));
    }

    @Test
    void levelCountCountsStackedSpansPerColumn() {
        SpanGrid grid = new GridFixture().layer(64, "SS").layer(70, "S.").layer(76, "S.").spanGrid();
        RoadMask mask = RoadMask.of(grid, allSpans(grid, 1, 0, 64, 70, 76));

        assertEquals(4, mask.size());
        assertEquals(3, mask.levelCount());
        assertEquals(0, RoadMask.empty().levelCount());
        assertEquals(0, RoadMask.empty().size());
    }

    @Test
    void carriesGateTagsAndDistances() {
        SpanGrid grid = new GridFixture().layer(64, "SSS").gate(9, 1, 64, 0, 2).spanGrid();
        RoadMask mask = RoadMask.of(grid, allSpans(grid, 2, 0, 64));

        assertEquals(9, mask.gateDoor(mask.indexOf(1, 64, 0)));
        assertEquals(RoadMask.NONE, mask.gateDoor(mask.indexOf(0, 64, 0)));
        assertEquals(2.0, mask.distance(mask.indexOf(0, 64, 0), mask.indexOf(2, 64, 0)), 1e-9);
    }
}

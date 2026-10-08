package net.knightsandkings.knk.core.roads.build;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinningTest {

    static RoadMask mask(GridFixture fixture, int maxX, int maxZ, int... ys) {
        SpanGrid grid = fixture.spanGrid();
        return RoadMask.of(grid, RoadMaskTest.allSpans(grid, maxX, maxZ, ys));
    }

    static int count(boolean[] on) {
        int c = 0;
        for (boolean b : on) {
            if (b) c++;
        }
        return c;
    }

    /** Number of 8-connected components of the skeleton (over mask links). */
    static int components(RoadMask mask, boolean[] on) {
        boolean[] seen = new boolean[mask.size()];
        int components = 0;
        for (int i = 0; i < mask.size(); i++) {
            if (!on[i] || seen[i]) continue;
            components++;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(i);
            seen[i] = true;
            while (!q.isEmpty()) {
                int c = q.poll();
                for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
                    int nb = mask.neighbour(c, d);
                    if (nb != RoadMask.NONE && on[nb] && !seen[nb]) {
                        seen[nb] = true;
                        q.add(nb);
                    }
                }
            }
        }
        return components;
    }

    /** ASCII picture of one layer of a mask: '#' skeleton, '.' mask span, ' ' nothing. */
    static String render(RoadMask mask, boolean[] on, int y, int minX, int maxX, int minZ, int maxZ) {
        StringBuilder sb = new StringBuilder("\n");
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int i = mask.indexOf(x, y, z);
                sb.append(i == RoadMask.NONE ? ' ' : on[i] ? '#' : '.');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static int countDegree(RoadMask mask, boolean[] on, int degree) {
        int c = 0;
        for (int i = 0; i < mask.size(); i++) {
            if (on[i] && Thinning.degree(mask, on, i) == degree) c++;
        }
        return c;
    }

    @Test
    void oneWidePathIsItsOwnSkeleton() {
        RoadMask m = mask(new GridFixture().layer(64, "GGGGGGGGGG"), 9, 0, 64);
        boolean[] on = Thinning.thin(m);
        assertEquals(10, count(on));
        assertEquals(2, countDegree(m, on, 1));
        assertEquals(8, countDegree(m, on, 2));
    }

    @Test
    void meanderingOneWidePathKeepsEveryCornerThatIsNeeded() {
        GridFixture f = new GridFixture().layer(64,
            "GGGG....",
            "...G....",
            "...GGGG.",
            "......G.",
            "......GG");
        RoadMask m = mask(f, 7, 4, 64);
        boolean[] on = Thinning.thin(m);

        assertEquals(1, components(m, on));
        assertEquals(2, countDegree(m, on, 1), "two ends");
        assertEquals(0, countDegree(m, on, 3), "no junctions");
        // The four L-corners (3,0), (3,2), (6,2), (6,4) go: their two neighbours are diagonal
        // neighbours of each other through the corner itself. Both ends stay - (7,4) touches the
        // corner (6,3) diagonally and is exactly the corner end the thinning guard protects.
        String picture = render(m, on, 64, 0, 7, 0, 4);
        assertFalse(on[m.indexOf(3, 64, 0)], picture);
        assertFalse(on[m.indexOf(6, 64, 4)], picture);
        assertTrue(on[m.indexOf(2, 64, 0)], picture);
        assertTrue(on[m.indexOf(3, 64, 1)], picture);
        assertTrue(on[m.indexOf(0, 64, 0)], picture);
        assertTrue(on[m.indexOf(7, 64, 4)], picture);
        assertEquals(12 - 4, count(on), picture);
    }

    @Test
    void fiveWideRoadThinsToOneLineInTheMiddleRow() {
        GridFixture f = new GridFixture();
        for (int z = 0; z < 5; z++) f.layer(0, 64, z, "SSSSSSSSSSSSSSSSSSSS");
        RoadMask m = mask(f, 19, 4, 64);
        boolean[] on = Thinning.thin(m);

        String picture = render(m, on, 64, 0, 19, 0, 4);
        assertEquals(1, components(m, on), picture);
        for (int x = 4; x <= 15; x++) {
            assertTrue(on[m.indexOf(x, 64, 2)], "centre row at x=" + x + picture);
            assertFalse(on[m.indexOf(x, 64, 0)], picture);
            assertFalse(on[m.indexOf(x, 64, 1)], picture);
            assertFalse(on[m.indexOf(x, 64, 3)], picture);
            assertFalse(on[m.indexOf(x, 64, 4)], picture);
        }
        assertTrue(count(on) <= 24, "a line plus at most short forks at the ends" + picture);
        assertTrue(count(on) >= 14, picture);
    }

    @Test
    void twoWideRoadThinsToOneLine() {
        GridFixture f = new GridFixture().layer(64, "SSSSSSSSSS", "SSSSSSSSSS");
        RoadMask m = mask(f, 9, 1, 64);
        boolean[] on = Thinning.thin(m);

        assertEquals(1, components(m, on));
        assertEquals(0, countDegree(m, on, 3));
        assertEquals(2, countDegree(m, on, 1));
        assertTrue(count(on) >= 8 && count(on) <= 10, "got " + count(on));
    }

    @Test
    void tJunctionOnAThinRoadKeepsOneBranchPoint() {
        GridFixture f = new GridFixture().layer(64,
            "GGGGGGGGG",
            "....G....",
            "....G....",
            "....G....");
        RoadMask m = mask(f, 8, 3, 64);
        boolean[] on = Thinning.thin(m);

        String picture = render(m, on, 64, 0, 8, 0, 3);
        // 8-connectivity: (3,0) and (5,0) touch the stem's first span (4,1) diagonally, so the T's
        // centre (4,0) is redundant and Holt's pass removes it; the stem's first span is then the one
        // degree-3 span (3,0)-(4,1)-(5,0) plus the stem. Length changes by less than a block.
        assertEquals(11, count(on), picture);
        assertEquals(1, components(m, on), picture);
        assertEquals(1, countDegree(m, on, 3), picture);
        assertEquals(3, countDegree(m, on, 1), picture);
        assertFalse(on[m.indexOf(4, 64, 0)], picture);
        assertTrue(on[m.indexOf(4, 64, 1)], picture);
    }

    @Test
    void blockCollapsesToAtMostTwoSpans() {
        RoadMask m = mask(new GridFixture().layer(64, "SSS", "SSS", "SSS"), 2, 2, 64);
        boolean[] on = Thinning.thin(m);
        // One span, or two touching ones (the corner-end guard may keep a last pair); such a blob is
        // shorter than minSpurLength and yields no edge either way.
        assertTrue(count(on) >= 1 && count(on) <= 2, render(m, on, 64, 0, 2, 0, 2));
        assertEquals(1, components(m, on));
    }

    @Test
    void diagonalBandBecomesACleanDiagonal() {
        GridFixture f = new GridFixture().layer(64,
            "SS......",
            "SSS.....",
            ".SSS....",
            "..SSS...",
            "...SSS..",
            "....SSS.",
            ".....SSS",
            "......SS");
        RoadMask m = mask(f, 7, 7, 64);
        boolean[] on = Thinning.thin(m);

        assertEquals(1, components(m, on));
        assertEquals(0, countDegree(m, on, 3), "no staircase corners left");
        assertEquals(2, countDegree(m, on, 1));
        assertTrue(count(on) <= 9, "at most a diagonal of 8 plus one, got " + count(on));
    }

    @Test
    void staircaseCornerRemovalKeepsConnectivityAndLineEnds() {
        // Hand-made 4-connected staircase (0,0) (1,0) (1,1) (1,2) (2,2) (2,3): the two L-corners
        // (1,0) and (1,2) go, both ends stay.
        RoadMask m = mask(new GridFixture().layer(64, "SS.", ".S.", ".SS", "..S"), 2, 3, 64);
        boolean[] on = new boolean[m.size()];
        java.util.Arrays.fill(on, true);
        Thinning.removeStaircaseCorners(m, on);

        String picture = render(m, on, 64, 0, 2, 0, 3);
        assertEquals(4, count(on), picture);
        assertTrue(on[m.indexOf(0, 64, 0)], "the line end stays" + picture);
        assertTrue(on[m.indexOf(1, 64, 1)], picture);
        assertTrue(on[m.indexOf(2, 64, 2)], picture);
        assertTrue(on[m.indexOf(2, 64, 3)], "the line end stays" + picture);
        assertEquals(1, components(m, on));
        assertEquals(2, countDegree(m, on, 1));
    }

    @Test
    void staircaseRemovalLeavesAStraightLineAloneAndCutsAnLCorner() {
        RoadMask line = mask(new GridFixture().layer(64, "SSSSS"), 4, 0, 64);
        boolean[] on = new boolean[line.size()];
        java.util.Arrays.fill(on, true);
        Thinning.removeStaircaseCorners(line, on);
        assertEquals(5, count(on));

        // An L's corner is cut (its two neighbours are diagonal neighbours through it); the legs stay.
        RoadMask l = mask(new GridFixture().layer(64, "SSS", "S..", "S.."), 2, 2, 64);
        boolean[] onL = new boolean[l.size()];
        java.util.Arrays.fill(onL, true);
        Thinning.removeStaircaseCorners(l, onL);
        assertEquals(4, count(onL), render(l, onL, 64, 0, 2, 0, 2));
        assertFalse(onL[l.indexOf(0, 64, 0)]);
        assertEquals(1, components(l, onL));
        assertEquals(2, countDegree(l, onL, 1));
    }

    @Test
    void stackedRoadsAreThinnedIndependently() {
        GridFixture f = new GridFixture();
        for (int z = 0; z < 3; z++) {
            f.layer(0, 64, z, "SSSSSSSSSS");
            f.layer(0, 70, z, "SSSSSSSSSS");
        }
        RoadMask m = mask(f, 9, 2, 64, 70);
        boolean[] on = Thinning.thin(m);

        assertEquals(2, components(m, on));
        for (int x = 2; x <= 7; x++) {
            assertTrue(on[m.indexOf(x, 64, 1)]);
            assertTrue(on[m.indexOf(x, 70, 1)]);
        }
    }

    @Test
    void emptyMaskThinsToNothing() {
        assertEquals(0, Thinning.thin(RoadMask.empty()).length);
    }
}

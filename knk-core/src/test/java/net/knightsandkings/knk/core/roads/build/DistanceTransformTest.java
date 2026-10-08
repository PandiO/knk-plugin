package net.knightsandkings.knk.core.roads.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DistanceTransformTest {

    private static RoadMask mask(GridFixture fixture, int maxX, int maxZ, int... ys) {
        SpanGrid grid = fixture.spanGrid();
        return RoadMask.of(grid, RoadMaskTest.allSpans(grid, maxX, maxZ, ys));
    }

    @Test
    void oneWidePathIsAllOnes() {
        RoadMask m = mask(new GridFixture().layer(64, "GGGGGGG"), 6, 0, 64);
        int[] dt = DistanceTransform.compute(m);
        for (int i = 0; i < m.size(); i++) {
            assertEquals(1, dt[i]);
        }
        assertEquals(1, DistanceTransform.width(1));
        assertEquals(0.5, DistanceTransform.halfWidth(1));
    }

    @Test
    void fiveWideRoadPeaksAtThreeInTheMiddleRow() {
        GridFixture f = new GridFixture();
        for (int z = 0; z < 5; z++) {
            f.layer(0, 64, z, "SSSSSSSSSSSSSSS");
        }
        RoadMask m = mask(f, 14, 4, 64);
        int[] dt = DistanceTransform.compute(m);

        assertEquals(1, dt[m.indexOf(7, 64, 0)]);
        assertEquals(2, dt[m.indexOf(7, 64, 1)]);
        assertEquals(3, dt[m.indexOf(7, 64, 2)]);
        assertEquals(2, dt[m.indexOf(7, 64, 3)]);
        assertEquals(1, dt[m.indexOf(7, 64, 4)]);
        assertEquals(1, dt[m.indexOf(0, 64, 2)], "the road's end is a border too");
        assertEquals(5, DistanceTransform.width(3));
        assertEquals(2.5, DistanceTransform.halfWidth(3));
    }

    @Test
    void distanceIsChebyshev() {
        GridFixture f = new GridFixture();
        for (int z = 0; z < 7; z++) {
            f.layer(0, 64, z, "SSSSSSS");
        }
        RoadMask m = mask(f, 6, 6, 64);
        int[] dt = DistanceTransform.compute(m);

        assertEquals(4, dt[m.indexOf(3, 64, 3)]);
        assertEquals(2, dt[m.indexOf(1, 64, 1)], "diagonal steps count one");
        assertEquals(3, dt[m.indexOf(2, 64, 4)]);
    }

    @Test
    void holesAreBordersToo() {
        GridFixture f = new GridFixture()
            .layer(64, "SSSSS", "SSSSS", "SS.SS", "SSSSS", "SSSSS");
        RoadMask m = mask(f, 4, 4, 64);
        int[] dt = DistanceTransform.compute(m);

        assertEquals(1, dt[m.indexOf(1, 64, 1)], "next to the hole");
        assertEquals(1, dt[m.indexOf(2, 64, 1)]);
    }

    @Test
    void spanGridLinksNotColumnsDecideAdjacency() {
        // Two 3-wide roads stacked far apart: each has its own distance field.
        GridFixture f = new GridFixture()
            .layer(64, "SSSSS", "SSSSS", "SSSSS")
            .layer(70, "SSSSS", "SSSSS", "SSSSS");
        RoadMask m = mask(f, 4, 2, 64, 70);
        int[] dt = DistanceTransform.compute(m);

        assertEquals(2, dt[m.indexOf(2, 64, 1)]);
        assertEquals(2, dt[m.indexOf(2, 70, 1)]);
        assertEquals(1, dt[m.indexOf(2, 70, 0)]);
    }

    @Test
    void isolatedSpanIsOne() {
        RoadMask m = mask(new GridFixture().layer(64, "S"), 0, 0, 64);
        assertEquals(1, DistanceTransform.compute(m)[0]);
        assertEquals(0, DistanceTransform.compute(RoadMask.empty()).length);
    }
}

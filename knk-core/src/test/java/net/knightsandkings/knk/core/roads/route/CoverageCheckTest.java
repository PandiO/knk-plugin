package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.roads.survey.SurveySample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageCheckTest {

    private final RoadNetworkSnapshot town = NetworkFixture.town();

    private static SurveySample crumb(int x, int y, int z, String floor) {
        List<String> offsets = new ArrayList<>(Collections.nCopies(SurveySample.WIDTH, (String) null));
        offsets.set(SurveySample.CENTRE_INDEX, floor);
        return new SurveySample(x, y, z, true, floor, null, offsets);
    }

    @Test
    void breadcrumbsFarFromEveryEdgeAreMissesWithTheirFloor() {
        List<SurveySample> walk = List.of(
            crumb(10, 64, 0, "STONE_BRICKS"),      // on A–B
            crumb(20, 64, 2, "STONE_BRICKS"),      // 2 blocks beside it: covered (limit inclusive)
            crumb(30, 64, 5, "DIRT"),              // 5 beside: a miss
            crumb(40, 66, 0, "OAK_PLANKS"),        // 2 above the road: covered (no vertical weight)
            crumb(50, 67, 0, "OAK_PLANKS"),        // 3 above: a miss
            crumb(300, 64, 300, "GRASS_BLOCK"));   // nowhere near
        List<CoverageCheck.Miss> misses = CoverageCheck.misses(walk, town);
        assertEquals(3, misses.size());
        assertEquals("DIRT", misses.get(0).floor());
        assertEquals(30, misses.get(0).x());
        assertEquals("OAK_PLANKS", misses.get(1).floor());
        assertEquals("GRASS_BLOCK", misses.get(2).floor());
        assertTrue(Double.isInfinite(misses.get(2).distance()));
        assertEquals(0.5, CoverageCheck.coverage(walk, town, CoverageCheck.DEFAULT_MAX_DISTANCE), 1e-9);
        assertEquals(1.0, CoverageCheck.coverage(List.of(), town, 2), 1e-9);
        assertEquals(1, CoverageCheck.misses(walk, town, 5).size(), "a wider tolerance covers the 5-beside and 3-above");
        assertEquals(walk.size(), CoverageCheck.misses(walk, RoadNetworkSnapshot.empty("w")).size());
    }
}

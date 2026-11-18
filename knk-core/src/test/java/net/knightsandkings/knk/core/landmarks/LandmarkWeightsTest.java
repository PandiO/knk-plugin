package net.knightsandkings.knk.core.landmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.model.Landmark;
import net.knightsandkings.knk.core.roads.route.RegionShape;
import net.knightsandkings.knk.core.roads.walk.WalkCellWeights;

/** Off-road phase 5.2 (PandiO/knk-workspace#709, DESIGN § 5.4.3, § 5.4.4): landmark preference weights on walk cells. */
class LandmarkWeightsTest {

    private static final int[] EVERYWHERE = {-1000, -64, -1000, 1000, 320, 1000};

    private static Landmark landmark(int id, String world, Landmark.Extent extent, String regionId, Double radius,
                                     double preference) {
        return new Landmark(id, "L" + id, world, 10.5, 64, 10.5, extent, regionId, radius, null, null, true, preference);
    }

    private static LandmarkWeights.Area cylinder(int id, double x, double y, double z, double r, double preference) {
        return new LandmarkWeights.Area(id, new LandmarkWeights.CylinderArea(new LandmarkExtent.Cylinder(x, y, z, r)),
            preference);
    }

    private static LandmarkWeights.Area cuboid(int id, int x0, int z0, int x1, int z1, double preference) {
        return new LandmarkWeights.Area(id, new LandmarkWeights.RegionArea(RegionShape.cuboid(x0, 60, z0, x1, 80, z1)),
            preference);
    }

    @Test
    void onlyLandmarksWithAnExtentAndAWeightInTheWorldBecomeAreas() {
        RegionShape hall = RegionShape.cuboid(0, 60, 0, 20, 80, 20);
        Map<String, RegionShape> regions = Map.of("hall", hall);
        List<Landmark> landmarks = List.of(
            landmark(1, "world", Landmark.Extent.RADIUS, null, 5.0, 2.0),
            landmark(2, "WORLD", Landmark.Extent.REGION, "hall", null, 0.5),
            landmark(3, "world", Landmark.Extent.REGION, "unknown", null, 2.0),
            landmark(4, "world", Landmark.Extent.NONE, null, null, 2.0),
            landmark(5, "world", Landmark.Extent.RADIUS, null, 5.0, 1.0),
            landmark(6, "nether", Landmark.Extent.RADIUS, null, 5.0, 2.0));

        List<LandmarkWeights.Area> areas = LandmarkWeights.areas(landmarks, "world",
            id -> Optional.ofNullable(regions.get(id)));

        assertEquals(List.of(1, 2), areas.stream().map(LandmarkWeights.Area::landmarkId).toList(),
            "a radius and a known region, any case of the world; an unknown region, no extent, weight 1.0 and another "
                + "world are left out");
        assertEquals(2.0, areas.get(0).preference());
        assertSame(hall, ((LandmarkWeights.RegionArea) areas.get(1).shape()).region());
        assertTrue(LandmarkWeights.areas(null, "world", id -> Optional.empty()).isEmpty());
    }

    @Test
    void aCellInsideAnAreaCostsItsPreferenceOutsideOne() {
        WalkCellWeights cells = LandmarkWeights.of(List.of(cylinder(1, 10.5, 65, 10.5, 3, 2.0)), 4.0).within(EVERYWHERE);

        assertEquals(2.0, cells.multiplier(10, 65, 10), "the pin's own column");
        assertEquals(2.0, cells.multiplier(12, 65, 10), "within 3 across");
        assertEquals(1.0, cells.multiplier(14, 65, 10), "beyond the radius");
        assertEquals(1.0, cells.multiplier(10, 69, 10), "above the cylinder's height");
        assertEquals(1.0, cells.minMultiplier(), "every weight above 1: the heuristic is not raised");
    }

    @Test
    void aRegionCountsByTheFeetBlock() {
        WalkCellWeights cells = LandmarkWeights.of(List.of(cuboid(1, 0, 0, 4, 4, 3.0)), 4.0).within(EVERYWHERE);

        assertEquals(3.0, cells.multiplier(4, 65, 4), "bounds inclusive");
        assertEquals(1.0, cells.multiplier(5, 65, 4));
        assertEquals(1.0, cells.multiplier(2, 81, 2), "above the y band");
    }

    @Test
    void overlappingAreasMultiplyCappedByPreferenceMaxBothWays() {
        LandmarkWeights up = LandmarkWeights.of(List.of(cuboid(1, 0, 0, 10, 10, 3.0), cuboid(2, 5, 0, 15, 10, 3.0)), 4.0);
        WalkCellWeights cells = up.within(EVERYWHERE);
        assertEquals(3.0, cells.multiplier(2, 65, 2), "one area");
        assertEquals(4.0, cells.multiplier(7, 65, 2), "3 x 3 = 9, capped at preference-max 4");

        WalkCellWeights wider = LandmarkWeights.of(up.areas(), 10.0).within(EVERYWHERE);
        assertEquals(9.0, wider.multiplier(7, 65, 2), 1e-12, "a higher cap lets the product through");

        WalkCellWeights down = LandmarkWeights.of(List.of(cuboid(1, 0, 0, 10, 10, 0.25), cuboid(2, 5, 0, 15, 10, 0.25)),
            4.0).within(EVERYWHERE);
        assertEquals(0.25, down.multiplier(2, 65, 2));
        assertEquals(0.25, down.multiplier(7, 65, 2), "0.0625 held at 1 / preference-max");
        assertEquals(0.25, down.minMultiplier(), "the heuristic's scale is held at the same floor");

        WalkCellWeights mixed = LandmarkWeights.of(List.of(cuboid(1, 0, 0, 10, 10, 2.0), cuboid(2, 5, 0, 15, 10, 0.5)),
            4.0).within(EVERYWHERE);
        assertEquals(1.0, mixed.multiplier(7, 65, 2), 1e-12, "2 x 0.5");
    }

    @Test
    void theHeuristicScaleIsTheSmallestMultiplierInForce() {
        WalkCellWeights cells = LandmarkWeights.of(List.of(cuboid(1, 0, 0, 4, 4, 0.8), cuboid(2, 20, 0, 24, 4, 0.5),
            cuboid(3, 40, 0, 44, 4, 3.0)), 4.0).within(EVERYWHERE);

        assertEquals(0.4, cells.minMultiplier(), 1e-12, "0.8 x 0.5, as if they overlapped; 3.0 does not count");
        for (int x = -2; x <= 46; x++) {
            assertTrue(cells.multiplier(x, 65, 2) >= cells.minMultiplier(), "no cell below the bound at x " + x);
        }
    }

    @Test
    void onlyTheAreasTheRequestCanReachAreKept() {
        LandmarkWeights weights = LandmarkWeights.of(List.of(cuboid(1, 0, 0, 4, 4, 2.0), cuboid(2, 100, 100, 104, 104,
            0.5)), 4.0);

        assertSame(WalkCellWeights.NONE, weights.within(new int[] {200, 60, 200, 260, 70, 260}), "nothing near: none");
        WalkCellWeights near = weights.within(new int[] {-10, 60, -10, 10, 70, 10});
        assertEquals(2.0, near.multiplier(2, 65, 2));
        assertEquals(1.0, near.multiplier(102, 65, 102), "the far area was dropped");
        assertEquals(1.0, near.minMultiplier(), "and so was its 0.5");
        assertSame(WalkCellWeights.NONE, LandmarkWeights.NONE.within(EVERYWHERE));
    }

    @Test
    void aBadCapIsTheDefaultAndABadPreferenceIsRefused() {
        assertEquals(4.0, LandmarkWeights.validMax(0.5));
        assertEquals(4.0, LandmarkWeights.validMax(Double.NaN));
        assertEquals(1.0, LandmarkWeights.validMax(1.0));
        assertEquals(4.0, LandmarkWeights.of(List.of(), Double.POSITIVE_INFINITY).preferenceMax());
        assertThrows(IllegalArgumentException.class, () -> cuboid(1, 0, 0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> cuboid(1, 0, 0, 1, 1, Double.NaN));
    }

    @Test
    void theModelReadsAMissingOrBrokenPreferenceAsNoWeight() {
        assertEquals(1.0, landmark(1, "world", Landmark.Extent.RADIUS, null, 5.0, 0).preference());
        assertEquals(1.0, landmark(1, "world", Landmark.Extent.RADIUS, null, 5.0, Double.NaN).preference());
        assertEquals(1.0, new Landmark(1, "A", "world", 0, 0, 0, Landmark.Extent.RADIUS, null, 5.0, null, null, true)
            .preference(), "the phase 5.1 constructor");
        assertEquals(1.0, Landmark.preference(null));
        assertTrue(landmark(1, "world", Landmark.Extent.RADIUS, null, 5.0, 2.0).weighted());
        assertTrue(!landmark(1, "world", Landmark.Extent.NONE, null, null, 2.0).weighted());
    }
}

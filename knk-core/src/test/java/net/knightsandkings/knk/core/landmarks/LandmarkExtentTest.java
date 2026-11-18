package net.knightsandkings.knk.core.landmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.model.Landmark;

/** Off-road phase 5.1 (PandiO/knk-workspace#708, DESIGN § 6.3): the extent of a landmark and the cylinder test (A13). */
class LandmarkExtentTest {

    private static Landmark landmark(Landmark.Extent extent, String region, Double radius) {
        return new Landmark(1, "x", "world", 10, 64, 10, extent, region, radius, null, null, true);
    }

    @Test
    void theExtentFollowsTheKind() {
        assertInstanceOf(LandmarkExtent.Point.class, LandmarkExtent.of(landmark(Landmark.Extent.NONE, null, null)));
        assertEquals(new LandmarkExtent.Region("hall"), LandmarkExtent.of(landmark(Landmark.Extent.REGION, "hall", null)));
        assertEquals(new LandmarkExtent.Cylinder(10, 64, 10, 5), LandmarkExtent.of(landmark(Landmark.Extent.RADIUS, null, 5.0)));
        assertInstanceOf(LandmarkExtent.Point.class, LandmarkExtent.of(landmark(Landmark.Extent.REGION, null, null)));
        assertInstanceOf(LandmarkExtent.Point.class, LandmarkExtent.of(landmark(Landmark.Extent.RADIUS, null, 0.0)));
        assertInstanceOf(LandmarkExtent.Point.class, LandmarkExtent.of(landmark(Landmark.Extent.RADIUS, null, null)));
    }

    @Test
    void theCylinderHoldsItsRadiusAcrossAndAsMuchUpAndDown() {
        LandmarkExtent.Cylinder cylinder = new LandmarkExtent.Cylinder(10, 64, 10, 5);
        assertTrue(cylinder.contains(10, 64, 10));
        assertTrue(cylinder.contains(13, 64, 14), "3-4-5: on the edge");
        assertFalse(cylinder.contains(14, 64, 14), "outside across");
        assertTrue(cylinder.contains(10, 69, 10));
        assertFalse(cylinder.contains(10, 69.5, 10), "a floor far above");
        assertFalse(cylinder.contains(10, 58, 10), "a floor far below");
        assertThrows(IllegalArgumentException.class, () -> new LandmarkExtent.Cylinder(0, 0, 0, 0));
    }

    @Test
    void v1HasNoStoredLinks() {
        assertTrue(LandmarkConnections.INFERRED.linksOf(1).isEmpty());
    }
}

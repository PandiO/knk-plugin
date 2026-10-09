package net.knightsandkings.knk.core.roads.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionShapeTest {

    private final RegionShape cuboid = RegionShape.cuboid(80, 60, 180, 120, 70, 220);
    private final RegionShape triangle = RegionShape.polygon(List.of(new double[] {0, 0}, new double[] {100, 0},
        new double[] {0, 100}), 60, 70);

    @Test
    void cuboidContainsInclusiveBoundsUsingFeet() {
        assertTrue(cuboid.containsColumn(80, 180));
        assertTrue(cuboid.containsColumn(120, 220));
        assertFalse(cuboid.containsColumn(121, 200));
        assertTrue(cuboid.contains(100, 60, 200));
        assertFalse(cuboid.contains(100, 59, 200));
        assertTrue(cuboid.containsFloor(100, 59, 200), "floor 59 → feet 60, the band's bottom");
        assertTrue(cuboid.containsFloor(100, 69, 200));
        assertFalse(cuboid.containsFloor(100, 70, 200), "floor 70 → feet 71, above the band");
    }

    @Test
    void aPlayerCountsByTheBlockTheirFeetAreIn() {
        assertTrue(cuboid.containsFeet(120.7, 64, 200), "block 120 is the box's last column");
        assertFalse(cuboid.contains(120.7, 64, 200), "geometrically past the edge");
        assertTrue(cuboid.containsFeet(100.5, 70.5, 200.5), "feet on a slab in the band's top block");
        assertFalse(cuboid.containsFeet(121.0, 64, 200));
        assertFalse(cuboid.containsFeet(100.5, 71, 200.5));
        assertTrue(triangle.containsFeet(49.9, 65, 50.3), "block (49, 50) touches the hypotenuse");
    }

    @Test
    void polygonUsesPolygon2D() {
        assertTrue(triangle.containsColumn(10, 10));
        assertTrue(triangle.containsColumn(50, 50), "on the hypotenuse counts as inside");
        assertFalse(triangle.containsColumn(60, 60));
        assertFalse(triangle.containsColumn(-1, 10));
        assertEquals(4, cuboid.points().size());
        assertEquals(60, triangle.minY());
        assertEquals(70, triangle.maxY());
    }

    @Test
    void distanceAndClosestPointFromAFloorPosition() {
        assertEquals(0, cuboid.distanceFromFloor(100, 64, 200), 1e-9);
        assertEquals(10, cuboid.distanceFromFloor(130, 64, 200), 1e-9, "10 east of the box");
        assertEquals(5, cuboid.distanceFromFloor(100, 54, 200), 1e-9, "feet 55, band starts at 60");
        assertEquals(Math.hypot(10, 5), cuboid.distanceFromFloor(130, 54, 200), 1e-9);
        assertArrayEquals(new double[] {120, 64, 200}, cuboid.closestPointFromFloor(130, 64, 200), 1e-9);
        assertArrayEquals(new double[] {100, 59, 200}, cuboid.closestPointFromFloor(100, 40, 200), 1e-9,
            "y clamped to the band: feet 60 → floor 59");
        assertArrayEquals(new double[] {100, 64, 200}, cuboid.closestPointFromFloor(100, 64, 200), 1e-9);
        assertArrayEquals(new double[] {100, 64, 200}, cuboid.centre(), 1e-9);
    }

    @Test
    void invalidShapesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RegionShape.cuboid(10, 0, 0, 0, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> RegionShape.cuboid(0, 10, 0, 10, 0, 10));
        assertThrows(IllegalArgumentException.class,
            () -> RegionShape.polygon(List.of(new double[] {0, 0}, new double[] {1, 1}), 0, 1));
    }
}

package net.knightsandkings.knk.core.territory;

import static net.knightsandkings.knk.core.territory.TerritoryFixtures.cuboid;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.polygon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/** G11: the distance is measured from the nearest edge of the region, so the border follows its shape. */
class TerritoryShapeTest {

    @Test
    void insideAndOnTheEdge_isZero_outsideIsTheDistanceToTheNearestEdge() {
        TerritoryShape shape = TerritoryShape.of(List.of(cuboid("d", 0, 0, 15, 15)));

        assertEquals(0, shape.distance(5, 5));
        assertEquals(0, shape.distance(15, 0));
        assertEquals(10, shape.distance(25, 7), 1e-9);
        assertEquals(10, shape.distance(7, -10), 1e-9);
        assertEquals(5, shape.distance(18, 19), 1e-9); // a corner: 3-4-5
    }

    @Test
    void aPolygon_followsItsSlantedEdge() {
        // a right triangle with the hypotenuse from (0, 20) to (20, 0)
        TerritoryShape shape = TerritoryShape.of(List.of(polygon("p", 0, 0, 20, 0, 0, 20)));

        assertEquals(0, shape.distance(5, 5));
        assertEquals(Math.sqrt(2) * 5, shape.distance(15, 15), 1e-9);
    }

    @Test
    void subRegions_countAsPartOfTheShape_andTheBoundsCoverAll() {
        TerritoryShape shape = TerritoryShape.of(List.of(cuboid("main", 0, 0, 9, 9), cuboid("sub", 100, 0, 109, 9)));

        assertEquals(0, shape.distance(105, 5));
        assertEquals(0, shape.minX());
        assertEquals(109, shape.maxX());
    }

    @Test
    void noUsableOutline_isNoShape() {
        assertNull(TerritoryShape.of(List.of(polygon("broken", 0, 0, 5, 5))));
    }
}

package net.knightsandkings.knk.core.territory;

import static net.knightsandkings.knk.core.territory.TerritoryFixtures.WORLD;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.build;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.cuboid;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.district;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.placed;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.polygon;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Map outlines straight from the cache (territory DESIGN § 1; overview map O15). */
class TerritoryOutlinesTest {

    @Test
    void oneWholeChunk_isOneSquareRingOfFourCorners() {
        TerritoryCache cache = build(List.of(), placed(district(10, "d", 1, 0), cuboid("d", 0, 0, 15, 15)));

        List<TerritoryOutlines.Outline> outlines = TerritoryOutlines.build(cache);

        assertEquals(1, outlines.size());
        TerritoryOutlines.Outline outline = outlines.get(0);
        assertEquals(TerritoryHolder.town(1), outline.owner());
        assertEquals(WORLD, outline.world());
        assertEquals(1, outline.rings().size());
        TerritoryOutlines.Ring ring = outline.rings().get(0);
        assertFalse(ring.hole());
        assertEquals(4, ring.points().size());
        assertArrayEquals(new int[] { 0, 0 }, ring.points().stream().filter(p -> p[0] == 0 && p[1] == 0).findFirst().orElseThrow());
        assertTrue(ring.points().stream().anyMatch(p -> p[0] == 16 && p[1] == 16));
    }

    @Test
    void aSplitBorder_keepsItsBlockSteps_andTheRingIsClosedAndClockwise() {
        // range 0 on a triangle: the slanted edge is the border, so the outline is a staircase
        TerritoryCache cache = build(List.of(), placed(district(10, "tri", 1, 0), polygon("tri", 0, 0, 64, 0, 0, 64)));

        TerritoryOutlines.Ring ring = TerritoryOutlines.build(cache).get(0).rings().get(0);

        assertFalse(ring.hole());
        assertTrue(ring.points().size() > 100, "a step per block along the slant, not per chunk: " + ring.points().size());
        assertTrue(TerritoryOutlines.area(ring.points()) > 0);
        // twice the area of the staircase triangle: 65 columns on row 0 down to 1 on row 64 = 65 * 66 / 2 blocks
        assertEquals(2L * 65 * 66 / 2, TerritoryOutlines.area(ring.points()));
    }

    @Test
    void contestedLand_isItsOwnArea_betweenTheTwoTowns() {
        TerritoryCache cache = build(List.of(), placed(district(10, "p", 1, 40), cuboid("p", 0, 0, 15, 15)),
                placed(district(20, "r", 2, 40), cuboid("r", 64, 0, 79, 15)));

        List<TerritoryOutlines.Outline> outlines = TerritoryOutlines.build(cache);

        assertEquals(3, outlines.size());
        TerritoryOutlines.Outline contested = outlines.stream().filter(o -> o.owner() == null).findFirst().orElseThrow();
        assertEquals(List.of(TerritoryHolder.town(1), TerritoryHolder.town(2)), contested.contested());
        assertTrue(contested.pointCount() >= 4);
    }

    @Test
    void aRingAroundWilderness_isAHole() {
        // four districts in a ring with range 0 around an empty middle chunk
        TerritoryCache cache = build(List.of(),
                placed(district(1, "a", 1, 0), cuboid("a", 0, 0, 47, 15)),
                placed(district(2, "b", 1, 0), cuboid("b", 0, 32, 47, 47)),
                placed(district(3, "c", 1, 0), cuboid("c", 0, 16, 15, 31)),
                placed(district(4, "d", 1, 0), cuboid("d", 32, 16, 47, 31)));

        List<TerritoryOutlines.Ring> rings = TerritoryOutlines.build(cache).get(0).rings();

        assertEquals(2, rings.size());
        assertEquals(1, rings.stream().filter(TerritoryOutlines.Ring::hole).count());
        assertNull(cache.lookup(WORLD, 20, 20).owner());
    }
}

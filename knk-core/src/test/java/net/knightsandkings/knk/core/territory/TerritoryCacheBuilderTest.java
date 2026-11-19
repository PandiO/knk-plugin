package net.knightsandkings.knk.core.territory;

import static net.knightsandkings.knk.core.territory.TerritoryFixtures.WORLD;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.build;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.crownLand;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.cuboid;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.district;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.inputs;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.placed;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.polygon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The chunk cache (territory DESIGN § 1, G11, A05 lookup side; PandiO/knk-workspace#689). */
class TerritoryCacheBuilderTest {

    private static final TerritoryHolder PHOENIX = TerritoryHolder.town(1);
    private static final TerritoryHolder RIVERTOWN = TerritoryHolder.town(2);
    private static final TerritoryHolder AURELIA = TerritoryHolder.kingdom(7);

    @Test
    void aColumnWithinRangeOfTheNearestEdge_belongsToTheTown_theNextOneIsWilderness() {
        // district 0..15, range 40: x 55 is 40 blocks from the edge at x 15, x 56 is 41
        TerritoryCache cache = build(List.of(), placed(district(10, "d", 1, 40), cuboid("d", 0, 0, 15, 15)));

        assertEquals(PHOENIX, cache.lookup(WORLD, 55, 8).owner());
        assertTrue(cache.lookup(WORLD, 56, 8).isWilderness());
        assertEquals(PHOENIX, cache.lookup(WORLD, -40, 8).owner());
        assertTrue(cache.lookup(WORLD, -41, 8).isWilderness());
        assertTrue(cache.lookup("other_world", 5, 5).isWilderness());
    }

    @Test
    void borderChunks_areSplit_andWholeChunksAreNot() {
        TerritoryCache cache = build(List.of(), placed(district(10, "d", 1, 40), cuboid("d", 0, 0, 15, 15)));
        TerritoryMap map = cache.worlds().get(WORLD);

        assertFalse(map.isSplit(0, 0), "the district's own chunk");
        assertFalse(map.isSplit(1, 0), "16 to 31 is within 16 blocks");
        assertTrue(map.isSplit(3, 0), "the border at x 55/56 runs through chunk 3 (48 to 63)");
        assertTrue(map.chunkCount() > map.splitCount());
        assertEquals(PHOENIX, map.verdict(map.wholeIndex(TerritoryMap.chunkKey(0, 0))).owner());
    }

    @Test
    void theCapitalsLargerRange_reachesFurther() {
        // the API sends the capital's range already multiplied (G11): 100 × 1.5
        TerritoryCache cache = build(List.of(),
                placed(district(10, "normal", 1, 100), cuboid("normal", 0, 0, 15, 15)),
                placed(new TerritoryInputs.Source(TerritoryInputs.SourceType.DISTRICT, 11, "Old Town", WORLD, "capital",
                        List.of(), PHOENIX, true, 150), cuboid("capital", 1000, 0, 1015, 15)));

        assertTrue(cache.lookup(WORLD, 116, 8).isWilderness());
        assertEquals(PHOENIX, cache.lookup(WORLD, 1015 + 150, 8).owner());
        assertTrue(cache.lookup(WORLD, 1015 + 151, 8).isWilderness());
    }

    @Test
    void aDiagonalBorder_changesPerBlockColumnInsideTheSplitChunk() {
        // a triangle with its slanted edge from (0, 64) to (64, 0); range 0 makes the slant itself the border
        TerritoryCache cache = build(List.of(), placed(district(10, "tri", 1, 0), polygon("tri", 0, 0, 64, 0, 0, 64)));

        assertTrue(cache.isSplit(WORLD, 2, 1), "chunk (32..47, 16..31) is cut by x + z = 64");
        assertEquals(PHOENIX, cache.lookup(WORLD, 40, 24).owner()); // 64
        assertTrue(cache.lookup(WORLD, 41, 24).isWilderness());    // 65
        assertEquals(PHOENIX, cache.lookup(WORLD, 39, 25).owner()); // 64
        assertTrue(cache.lookup(WORLD, 40, 25).isWilderness());    // 65
    }

    @Test
    void crownLand_outranksTheTown_withoutAContest() {
        TerritoryCache cache = build(List.of(),
                placed(district(10, "d", 1, 100), cuboid("d", 0, 0, 15, 15)),
                placed(crownLand(20, "c", 7, 30), cuboid("c", 60, 0, 75, 15)));

        TerritoryVerdict verdict = cache.lookup(WORLD, 50, 8);
        assertEquals(AURELIA, verdict.owner());
        assertFalse(verdict.isContested());
        assertEquals(PHOENIX, cache.lookup(WORLD, 20, 8).owner());
    }

    @Test
    void twoTownsOverlapping_isContested_andAnAwardGivesItsChunksToOne() {
        TerritoryInputs.Source phoenix = district(10, "p", 1, 40);
        TerritoryInputs.Source rivertown = district(20, "r", 2, 40);
        TerritoryCache contested = build(List.of(), placed(phoenix, cuboid("p", 0, 0, 15, 15)),
                placed(rivertown, cuboid("r", 64, 0, 79, 15)));

        TerritoryVerdict middle = contested.lookup(WORLD, 40, 8);
        assertTrue(middle.isContested());
        assertEquals(List.of(PHOENIX, RIVERTOWN), middle.contesting());
        assertEquals("contested: Phoenix / Rivertown", contested.describe(middle));

        TerritoryInputs.Award award = new TerritoryInputs.Award(5, WORLD, RIVERTOWN, Set.of(TerritoryMap.chunkKey(2, 0)));
        TerritoryCache awarded = build(List.of(award), placed(phoenix, cuboid("p", 0, 0, 15, 15)),
                placed(rivertown, cuboid("r", 64, 0, 79, 15)));
        assertEquals(RIVERTOWN, awarded.lookup(WORLD, 40, 8).owner());
        assertEquals(2, awarded.lookup(WORLD, 40, 8).contesting().size());
    }

    @Test
    void wildernessChunks_areNotStored() {
        TerritoryCache cache = build(List.of(), placed(district(10, "d", 1, 0), cuboid("d", 0, 0, 15, 15)));
        TerritoryMap map = cache.worlds().get(WORLD);

        assertEquals(Set.of(TerritoryMap.chunkKey(0, 0)), map.wholeChunks());
        assertEquals(0, map.splitCount());
    }

    @Test
    void aSourceThatWouldPassTheChunkBudget_isLeftOut_andWarned() {
        TerritoryInputs.Source big = district(10, "big", 1, 1000);
        TerritoryInputs.Source small = district(11, "small", 2, 0);
        TerritoryCache cache = TerritoryCacheBuilder.build(inputs(List.of(big, small), List.of()),
                List.of(placed(big, cuboid("big", 0, 0, 15, 15)), placed(small, cuboid("small", 5000, 0, 5015, 15))),
                List.of("District#12 'gone': region gone not found"), 1L, 100);

        assertTrue(cache.lookup(WORLD, 20, 8).isWilderness());
        assertEquals(TerritoryHolder.town(2), cache.lookup(WORLD, 5005, 8).owner());
        assertEquals(2, cache.warnings().size());
        assertTrue(cache.warnings().get(1).contains("District#10"));
    }

    @Test
    void chunkKeys_roundTripNegativeCoordinates() {
        long key = TerritoryMap.chunkKey(-3, -70000);
        assertEquals(-3, TerritoryMap.chunkX(key));
        assertEquals(-70000, TerritoryMap.chunkZ(key));
        assertNull(TerritoryCache.EMPTY.lookup(WORLD, 0, 0).owner());
    }
}

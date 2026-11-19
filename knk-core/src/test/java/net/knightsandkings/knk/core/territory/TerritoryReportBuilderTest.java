package net.knightsandkings.knk.core.territory;

import static net.knightsandkings.knk.core.territory.TerritoryFixtures.WORLD;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.build;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.crownLand;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.cuboid;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.district;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.placed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The report the API turns into conflicts and structure flags (territory DESIGN § 3-4, A05). */
class TerritoryReportBuilderTest {

    private static final TerritoryHolder PHOENIX = TerritoryHolder.town(1);
    private static final TerritoryHolder RIVERTOWN = TerritoryHolder.town(2);

    private final TerritoryCache cache = build(List.of(),
            placed(district(10, "p", 1, 40), cuboid("p", 0, 0, 15, 15)),
            placed(district(20, "r", 2, 40), cuboid("r", 64, 0, 79, 15)));

    @Test
    void anEqualRankOverlap_isReportedOnceWithEveryChunkItTouches() {
        TerritoryReport report = TerritoryReportBuilder.build(cache, List.of(), Set.of(WORLD)).get(0);

        assertEquals(WORLD, report.world());
        assertEquals("v1", report.inputsVersion());
        assertEquals(1, report.overlaps().size());
        TerritoryReport.Overlap overlap = report.overlaps().get(0);
        assertEquals(PHOENIX, overlap.holderA());
        assertEquals(RIVERTOWN, overlap.holderB());
        // contested from x 24 (64 - 40) to x 55 (15 + 40): chunks 1, 2 and 3 in every chunk row the ranges cross
        assertTrue(overlap.chunks().stream().anyMatch(c -> c[0] == 1 && c[1] == 0));
        assertTrue(overlap.chunks().stream().anyMatch(c -> c[0] == 3 && c[1] == 0));
        assertTrue(overlap.chunks().stream().noneMatch(c -> c[0] == 0 || c[0] == 4));
        for (int i = 1; i < overlap.chunks().size(); i++) {
            int[] a = overlap.chunks().get(i - 1);
            int[] b = overlap.chunks().get(i);
            assertTrue(a[0] < b[0] || (a[0] == b[0] && a[1] < b[1]), "sorted by x, then z");
        }
    }

    @Test
    void townAgainstCrownLand_isNoOverlap() {
        TerritoryCache crown = build(List.of(), placed(district(10, "p", 1, 40), cuboid("p", 0, 0, 15, 15)),
                placed(crownLand(30, "c", 7, 40), cuboid("c", 64, 0, 79, 15)));

        assertTrue(TerritoryReportBuilder.build(crown, List.of(), Set.of(WORLD)).get(0).overlaps().isEmpty());
    }

    @Test
    void structures_areFlaggedContestedOrOutside_andLeftAloneInTheirOwnTerritory() {
        List<TerritoryReportBuilder.StructurePlacement> structures = List.of(
                new TerritoryReportBuilder.StructurePlacement(1, 1, WORLD, 5, 5),     // in Phoenix: nothing
                new TerritoryReportBuilder.StructurePlacement(2, 1, WORLD, 40, 5),    // contested
                new TerritoryReportBuilder.StructurePlacement(3, 1, WORLD, 70, 5),    // in Rivertown's territory
                new TerritoryReportBuilder.StructurePlacement(4, 1, WORLD, 500, 5),   // wilderness
                new TerritoryReportBuilder.StructurePlacement(5, null, WORLD, 500, 5), // town unknown
                new TerritoryReportBuilder.StructurePlacement(6, 1, "nether", 5, 5)); // another world

        List<TerritoryReport.StructureFlag> flags = TerritoryReportBuilder.build(cache, structures, Set.of(WORLD)).get(0)
                .structures();

        assertEquals(3, flags.size());
        assertEquals(2, flags.get(0).structureId());
        assertEquals(TerritoryReport.StructureFlag.CONTESTED, flags.get(0).flag());
        assertEquals(PHOENIX, flags.get(0).holderA());
        assertEquals(RIVERTOWN, flags.get(0).holderB());
        assertEquals(TerritoryReport.StructureFlag.OUTSIDE, flags.get(1).flag());
        assertEquals(3, flags.get(1).structureId());
        assertNull(flags.get(1).holderA());
        assertEquals(4, flags.get(2).structureId());
    }

    @Test
    void aWorldReportedBefore_getsAnEmptySnapshot_soItsConflictsAreUpdated() {
        List<TerritoryReport> reports = TerritoryReportBuilder.build(cache, List.of(), Set.of(WORLD, "old_world"));

        assertEquals(List.of("old_world", WORLD), reports.stream().map(TerritoryReport::world).toList());
        assertTrue(reports.get(0).overlaps().isEmpty());
        assertTrue(reports.get(0).structures().isEmpty());
    }
}

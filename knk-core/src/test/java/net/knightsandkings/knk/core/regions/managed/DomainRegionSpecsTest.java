package net.knightsandkings.knk.core.regions.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.arenas.ArenaFixtures;
import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaArea;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaAreaRegion;
import net.knightsandkings.knk.core.domain.districts.DistrictSummary;
import net.knightsandkings.knk.core.domain.structures.StructureSummary;
import net.knightsandkings.knk.core.domain.towns.TownSummary;

class DomainRegionSpecsTest {

    private static StructureSummary structure(int id, String region, Integer districtId) {
        return new StructureSummary(id, "S" + id, "", region, 1, 1, "Street", districtId, "D");
    }

    @Test
    void townDistrictStructureChainBecomesParentLinkedSpecs() {
        DomainRegionSpecs.Result result = DomainRegionSpecs.build(
                List.of(new TownSummary(1, "Rivia", "", "town_1")),
                List.of(new DistrictSummary(2, "Old Quarter", "", "district_2", 1, "Rivia")),
                List.of(structure(3, "domain_3", 2), structure(4, "domain_4", 2)),
                Map.of(3, "GateStructure", 4, "Shop"));

        assertEquals(4, result.specs().size());
        assertEquals(ManagedRegionKind.TOWN, result.specs().get(0).kind());
        assertEquals("town_1", result.specs().get(1).parentRegionId());
        ManagedRegionSpec gate = result.specs().get(2);
        assertEquals(ManagedRegionKind.GATE, gate.kind());
        assertEquals("district_2", gate.parentRegionId());
        assertEquals(ManagedRegionKind.STRUCTURE, result.specs().get(3).kind());   // unknown subtype: hierarchy only
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void domainsWithoutARegionAreSkippedAndStaleReferencesWarn() {
        DomainRegionSpecs.Result result = DomainRegionSpecs.build(
                List.of(new TownSummary(1, "Rivia", "", "  ")),
                List.of(new DistrictSummary(2, "Orphan", "", "district_2", 99, "?")),
                List.of(structure(3, "domain_3", 42)),
                Map.of());

        assertEquals(1, result.skipped().size());
        assertEquals(2, result.specs().size());
        assertEquals(null, result.specs().get(0).parentRegionId());
        assertEquals(null, result.specs().get(1).parentRegionId());
        assertEquals(2, result.warnings().size());
    }

    /**
     * Rev. 6: District -> Arena -> one spec per area region: battleground regions BATTLEGROUND, spectator-area regions
     * SPECTATOR_AREA, lobby regions LOBBY, all under the arena's region (a two-part battleground gives two specs).
     */
    @Test
    void areaRegionsHangUnderTheArenaRegionWithTheirKind() {
        KnkArena arena = ArenaFixtures.arena(5, "arena_2", List.of(
                ArenaFixtures.battleground(6, 5, "bg1", 0, null, null, "arena_5_battleground_60", "arena_5_battleground_61"),
                new KnkArenaArea(7, 5, KnkArenaArea.Kind.BATTLEGROUND, "bg2", null, 1, false, null, null, null, null, null,
                        List.of(new KnkArenaAreaRegion(70, " ", null))),
                ArenaFixtures.spectator(8, 5, "Stands", null, null, null, "arena_5_spectator_80"),
                ArenaFixtures.lobby(9, 5, "arena_5_lobby_90", null)));

        DomainRegionSpecs.Result result = DomainRegionSpecs.build(
                List.of(new TownSummary(1, "Rivia", "", "town_1")),
                List.of(new DistrictSummary(2, "Old Quarter", "", "district_2", 1, "Rivia")),
                List.of(structure(5, "arena_2", 2)),
                Map.of(5, "Arena"),
                List.of(arena));

        ManagedRegionSpec arenaSpec = result.specs().get(2);
        assertEquals(ManagedRegionKind.ARENA, arenaSpec.kind());
        assertEquals("district_2", arenaSpec.parentRegionId());
        List<ManagedRegionSpec> areas = result.specs().subList(3, result.specs().size());
        assertEquals(List.of("arena_5_battleground_60", "arena_5_battleground_61", "arena_5_spectator_80", "arena_5_lobby_90"),
                areas.stream().map(ManagedRegionSpec::regionId).toList());
        assertEquals(List.of(ManagedRegionKind.BATTLEGROUND, ManagedRegionKind.BATTLEGROUND, ManagedRegionKind.SPECTATOR_AREA,
                ManagedRegionKind.LOBBY), areas.stream().map(ManagedRegionSpec::kind).toList());
        assertTrue(areas.stream().allMatch(spec -> "arena_2".equals(spec.parentRegionId())));
        assertEquals("world", areas.getFirst().worldName());
        assertEquals(1, result.skipped().size());   // the region without an id
    }

    @Test
    void nullListsAreTreatedAsEmpty() {
        DomainRegionSpecs.Result result = DomainRegionSpecs.build(null, null, null, null);
        assertTrue(result.specs().isEmpty());
    }
}

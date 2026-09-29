package net.knightsandkings.knk.core.regions.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

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

    @Test
    void nullListsAreTreatedAsEmpty() {
        DomainRegionSpecs.Result result = DomainRegionSpecs.build(null, null, null, null);
        assertTrue(result.specs().isEmpty());
    }
}

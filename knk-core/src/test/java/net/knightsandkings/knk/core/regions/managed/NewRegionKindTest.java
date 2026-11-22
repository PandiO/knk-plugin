package net.knightsandkings.knk.core.regions.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.knightsandkings.knk.core.domain.districts.DistrictSummary;
import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;
import net.knightsandkings.knk.core.domain.structures.StructureSummary;
import net.knightsandkings.knk.core.domain.towns.TownSummary;

/**
 * PandiO/knk-workspace#991: a region renamed for a new domain is set up with the kind the startup repair gives it. Since
 * #991 the API sends each subtype under its own name (CrownLand, Shop, Warehouse, ProductionStructure) instead of
 * "Structure" or nothing, and the creation path must not manage what the repair leaves alone (crown land without
 * {@code production.enabled}, #689 A21).
 */
class NewRegionKindTest {

    private static final List<TownSummary> TOWNS = List.of(new TownSummary(1, "Phoenix", "", "domain_1", "world"));
    private static final List<DistrictSummary> DISTRICTS =
            List.of(new DistrictSummary(2, "Market", "", "domain_2", 1, "Phoenix", "world"));

    /** The kind the repair gives a structure of {@code type} in the Market district. */
    private static ManagedRegionKind repairKindOfStructure(String type, boolean production) {
        List<StructureSummary> structures =
                List.of(new StructureSummary(10, "Test", "", "domain_10", 1, 5, "Main", 2, "Market", null, 1));
        DomainRegionSpecs.Result result =
                DomainRegionSpecs.build(TOWNS, DISTRICTS, structures, Map.of(10, type), production);
        return result.specs().stream().filter(spec -> spec.regionId().equals("domain_10")).findFirst().orElseThrow().kind();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Structure", "GateStructure", "Shop", "Warehouse", "ProductionStructure"})
    void aNewStructureSubtype_getsTheRepairsKind_withAndWithoutProduction(String type) {
        for (boolean production : new boolean[] {false, true}) {
            assertEquals(Optional.of(repairKindOfStructure(type, production)),
                    ManagedRegionKind.forNewRegion(type, production), type + ", production " + production);
        }
    }

    @Test
    void theProductionKinds_onlyWithProduction() {
        assertEquals(Optional.of(ManagedRegionKind.RESOURCE_PRODUCTION), ManagedRegionKind.forNewRegion("ProductionStructure", true));
        assertEquals(Optional.of(ManagedRegionKind.STRUCTURE), ManagedRegionKind.forNewRegion("ProductionStructure", false));
        assertEquals(Optional.of(ManagedRegionKind.STRUCTURE), ManagedRegionKind.forNewRegion("Warehouse", true));
        assertEquals(Optional.of(ManagedRegionKind.STRUCTURE), ManagedRegionKind.forNewRegion("Shop", true));
    }

    @Test
    void crownLand_isSetUpAsTheRepairDoes_onlyWithProduction() {
        DomainRegionSpecs.Result repair = DomainRegionSpecs.crownLands(
                List.of(new KnkDomainSummary(20, "Crown", "CrownLand", null, null, "domain_20", "world", null, false)));

        assertEquals(Optional.of(repair.specs().get(0).kind()), ManagedRegionKind.forNewRegion("CrownLand", true));
        assertTrue(ManagedRegionKind.forNewRegion("CrownLand", false).isEmpty(),
                "without production.enabled the repair doesn't list crown land, so the creation path leaves it alone");
    }

    @Test
    void townAndDistrict_areUnchanged_andNoTypeIsLeftToTheRepair() {
        assertEquals(Optional.of(ManagedRegionKind.TOWN), ManagedRegionKind.forNewRegion("Town", false));
        assertEquals(Optional.of(ManagedRegionKind.DISTRICT), ManagedRegionKind.forNewRegion("District", true));
        assertTrue(ManagedRegionKind.forNewRegion(null, true).isEmpty());
        assertTrue(ManagedRegionKind.forNewRegion(" ", false).isEmpty());
    }
}

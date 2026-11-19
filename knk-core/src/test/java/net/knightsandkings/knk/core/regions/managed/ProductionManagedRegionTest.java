package net.knightsandkings.knk.core.regions.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.districts.DistrictSummary;
import net.knightsandkings.knk.core.domain.structures.StructureSummary;
import net.knightsandkings.knk.core.domain.towns.TownSummary;

/**
 * P2c (PandiO/knk-workspace#677, production DESIGN § 3.4, IMPLEMENTATION_PLAN § P2 "Plugin"): the managed-region kinds of
 * the production structure subtypes and rural structures without a parent, only with {@code production.enabled} (A21).
 */
class ProductionManagedRegionTest {

    private static final List<TownSummary> TOWNS = List.of(new TownSummary(1, "Phoenix", "", "town_1", "world"));
    private static final List<DistrictSummary> DISTRICTS =
            List.of(new DistrictSummary(2, "Market", "", "district_2", 1, "Phoenix", "world"));
    /** Market warehouse (in Market), Quarry (rural, Phoenix), Mill (production structure in Market). */
    private static final List<StructureSummary> STRUCTURES = List.of(
            new StructureSummary(10, "Market warehouse", "", "domain_10", 3, 5, "Main", 2, "Market", null, 1),
            new StructureSummary(11, "Quarry", "", "domain_11", 0, null, null, null, null, null, 1),
            new StructureSummary(12, "Mill", "", "domain_12", 2, 5, "Main", 2, "Market", null, 1));
    private static final Map<Integer, String> TYPES = Map.of(1, "Town", 2, "District", 10, "Warehouse",
            11, "ProductionStructure", 12, "ProductionStructure");

    private static ManagedRegionSpec spec(DomainRegionSpecs.Result result, String regionId) {
        return result.specs().stream().filter(spec -> spec.regionId().equals(regionId)).findFirst().orElseThrow();
    }

    @Test
    void theSubtypes_mapToTheirKinds_onlyWithProduction() {
        assertEquals(ManagedRegionKind.RESOURCE_PRODUCTION,
                ManagedRegionKind.fromDomainType("ProductionStructure", true).orElseThrow());
        assertEquals(ManagedRegionKind.STRUCTURE, ManagedRegionKind.fromDomainType("Warehouse", true).orElseThrow());
        assertEquals(ManagedRegionKind.GATE, ManagedRegionKind.fromDomainType("GateStructure", true).orElseThrow());
        assertEquals(ManagedRegionKind.STRUCTURE, ManagedRegionKind.fromDomainType("ProductionStructure", false).orElseThrow());
        assertEquals(ManagedRegionKind.STRUCTURE, ManagedRegionKind.fromDomainType("ProductionStructure").orElseThrow());
        assertTrue(ManagedRegionKind.fromDomainType(null, true).isEmpty());
    }

    @Test
    void withProduction_aRuralStructure_isTopLevel_inItsTownsWorld_withoutAWarning() {
        DomainRegionSpecs.Result result = DomainRegionSpecs.build(TOWNS, DISTRICTS, STRUCTURES, TYPES, true);

        ManagedRegionSpec quarry = spec(result, "domain_11");
        assertEquals(ManagedRegionKind.RESOURCE_PRODUCTION, quarry.kind());
        assertNull(quarry.parentRegionId());
        assertEquals("world", quarry.worldName());
        assertTrue(quarry.source().contains("rural, Town#1"));
        assertTrue(result.warnings().isEmpty(), () -> "warnings: " + result.warnings());

        ManagedRegionSpec warehouse = spec(result, "domain_10");
        assertEquals(ManagedRegionKind.STRUCTURE, warehouse.kind());
        assertEquals("district_2", warehouse.parentRegionId());
        ManagedRegionSpec mill = spec(result, "domain_12");
        assertEquals(ManagedRegionKind.RESOURCE_PRODUCTION, mill.kind());
        assertEquals("district_2", mill.parentRegionId());
    }

    @Test
    void withProductionOff_everythingIsAsBeforeP2c() {
        DomainRegionSpecs.Result off = DomainRegionSpecs.build(TOWNS, DISTRICTS, STRUCTURES, TYPES, false);
        DomainRegionSpecs.Result legacy = DomainRegionSpecs.build(TOWNS, DISTRICTS, STRUCTURES, TYPES);

        assertEquals(legacy, off);
        assertEquals(ManagedRegionKind.STRUCTURE, spec(off, "domain_11").kind());
        assertEquals(ManagedRegionKind.STRUCTURE, spec(off, "domain_12").kind());
        assertNull(spec(off, "domain_11").parentRegionId());
        assertTrue(off.warnings().stream().anyMatch(warning -> warning.contains("#11") && warning.contains("no parent set")));
    }

    @Test
    void aStructureWithNeitherDistrictNorTown_stillWarns_withProduction() {
        List<StructureSummary> orphan = List.of(new StructureSummary(13, "Lost", "", "domain_13", 0, null, null, null, null));

        DomainRegionSpecs.Result result = DomainRegionSpecs.build(TOWNS, DISTRICTS, orphan, Map.of(), true);

        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("#13")));
    }

    @Test
    void theRepair_givesTheRuralRegionItsFloorPriority_noParent_andTheResourceProductionFlags() {
        FakeWorldGuard wg = new FakeWorldGuard();
        for (String id : List.of("town_1", "district_2", "domain_10", "domain_11", "domain_12")) {
            wg.add(id);
        }
        DomainRegionSpecs.Result result = DomainRegionSpecs.build(TOWNS, DISTRICTS, STRUCTURES, TYPES, true);
        ManagedRegionReconciler reconciler = new ManagedRegionReconciler(new ManagedRegionPolicy(), Map.of());

        reconciler.reconcile(result.specs(), wg);

        FakeWorldGuard.Region quarry = wg.get("domain_11");
        assertNull(quarry.parent);
        assertEquals(30, quarry.priority);
        assertEquals("allow", String.valueOf(quarry.flags.get("entry")).toLowerCase());
        assertFalse(quarry.flags.containsKey("pvp"));
        assertFalse(quarry.flags.containsKey("block-break"));
        assertEquals("district_2", wg.get("domain_12").parent);
        assertEquals(30, wg.get("domain_12").priority);
        assertEquals("district_2", wg.get("domain_10").parent);
        assertTrue(wg.get("domain_10").flags.isEmpty());
    }

    @Test
    void aStructureMovedFromItsDistrictToRural_keepsItsOldParent_theRepairNeverClearsOne() {
        FakeWorldGuard wg = new FakeWorldGuard();
        wg.add("district_2");
        wg.add("domain_11").parent = "district_2";
        DomainRegionSpecs.Result result = DomainRegionSpecs.build(TOWNS, DISTRICTS, STRUCTURES, TYPES, true);

        new ManagedRegionReconciler(new ManagedRegionPolicy(), Map.of()).reconcile(result.specs(), wg);

        // documented in the managed regions page: remove it by hand with /rg setparent <region>
        assertEquals("district_2", wg.get("domain_11").parent);
        assertEquals(30, wg.get("domain_11").priority);
    }
}

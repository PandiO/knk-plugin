package net.knightsandkings.knk.core.regions.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.districts.DistrictDetail;
import net.knightsandkings.knk.core.domain.districts.DistrictSummary;
import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;
import net.knightsandkings.knk.core.domain.structures.StructureDetail;
import net.knightsandkings.knk.core.domain.structures.StructureSummary;
import net.knightsandkings.knk.core.domain.towns.TownDetail;
import net.knightsandkings.knk.core.domain.towns.TownSummary;
import net.knightsandkings.knk.core.ports.api.DistrictsQueryApi;
import net.knightsandkings.knk.core.ports.api.DomainCatalogQueryApi;
import net.knightsandkings.knk.core.ports.api.StructuresQueryApi;
import net.knightsandkings.knk.core.ports.api.TownsQueryApi;

/** The crown land managed-region kind (territory DESIGN § 2, G10; PandiO/knk-workspace#689, asked for by #326's L1). */
class CrownLandManagedRegionTest {

    private static <T> CompletableFuture<Page<T>> page(List<T> items) {
        return CompletableFuture.completedFuture(new Page<>(items, items.size(), 1, 100));
    }

    private static ManagedRegionSpecSource source(boolean includeCrownLand) {
        TownsQueryApi towns = new TownsQueryApi() {
            public CompletableFuture<Page<TownSummary>> search(PagedQuery q) {
                return page(List.of(new TownSummary(1, "Rivia", "", "town_1")));
            }

            public CompletableFuture<TownDetail> getById(int id) {
                return CompletableFuture.completedFuture(null);
            }
        };
        DistrictsQueryApi districts = new DistrictsQueryApi() {
            public CompletableFuture<Page<DistrictSummary>> search(PagedQuery q) {
                return page(List.of());
            }

            public CompletableFuture<DistrictDetail> getById(int id) {
                return CompletableFuture.completedFuture(null);
            }
        };
        StructuresQueryApi structures = new StructuresQueryApi() {
            public CompletableFuture<Page<StructureSummary>> search(PagedQuery q) {
                return page(List.of());
            }

            public CompletableFuture<StructureDetail> getById(int id) {
                return CompletableFuture.completedFuture(null);
            }
        };
        DomainCatalogQueryApi catalog = new DomainCatalogQueryApi() {
            public CompletableFuture<Page<KnkDomainSummary>> search(PagedQuery q) {
                return page(List.of(
                        new KnkDomainSummary(1, "Rivia", "Town", null, null, "town_1", "world", null, false),
                        new KnkDomainSummary(9, "Royal forest", "CrownLand", null, null, "domain_9", "world", null, false),
                        new KnkDomainSummary(10, "Unfinished", "CrownLand", null, null, null, null, null, false)));
            }

            public CompletableFuture<KnkDomainSummary> getById(int id) {
                return CompletableFuture.completedFuture(null);
            }
        };
        return new ManagedRegionSpecSource(towns, districts, structures, catalog, 100, includeCrownLand);
    }

    @Test
    void crownLand_isItsOwnKind_atTheTownsFloor_withoutFlags() {
        assertEquals(ManagedRegionKind.CROWN_LAND, ManagedRegionKind.fromDomainType("CrownLand").orElseThrow());
        assertEquals(ManagedRegionKind.CROWN_LAND, ManagedRegionKind.parse("crown_land").orElseThrow());
        assertEquals(10, ManagedRegionKind.CROWN_LAND.floorPriority());
        assertTrue(new ManagedRegionPolicy().flagRules(ManagedRegionKind.CROWN_LAND).isEmpty());
    }

    @Test
    void withProductionEnabled_theRepairManagesCrownLandRegions_topLevel() {
        DomainRegionSpecs.Result result = source(true).load().join();

        ManagedRegionSpec crown = result.specs().stream().filter(spec -> spec.kind() == ManagedRegionKind.CROWN_LAND)
                .findFirst().orElseThrow();
        assertEquals("domain_9", crown.regionId());
        assertNull(crown.parentRegionId());
        assertEquals("world", crown.worldName());
        assertTrue(result.skipped().stream().anyMatch(skip -> skip.label().contains("CrownLand#10")));
    }

    @Test
    void withProductionOff_crownLandRegionsAreLeftAlone() {
        DomainRegionSpecs.Result result = source(false).load().join();

        assertTrue(result.specs().stream().noneMatch(spec -> spec.kind() == ManagedRegionKind.CROWN_LAND));
        assertEquals(1, result.specs().size());
    }
}

package net.knightsandkings.knk.core.regions.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.logging.Logger;

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

class ManagedRegionRepairServiceTest {

    /** Serves {@code all} in pages of the requested size, page numbers from 1 like the API. */
    private static <T> Function<PagedQuery, CompletableFuture<Page<T>>> paged(List<T> all, List<Integer> requestedPages) {
        return query -> {
            requestedPages.add(query.pageNumber());
            int from = Math.min(all.size(), (query.pageNumber() - 1) * query.pageSize());
            int to = Math.min(all.size(), from + query.pageSize());
            return CompletableFuture.completedFuture(new Page<>(all.subList(from, to), all.size(), query.pageNumber(), query.pageSize()));
        };
    }

    private static TownsQueryApi towns(Function<PagedQuery, CompletableFuture<Page<TownSummary>>> search) {
        return new TownsQueryApi() {
            public CompletableFuture<Page<TownSummary>> search(PagedQuery q) { return search.apply(q); }
            public CompletableFuture<TownDetail> getById(int id) { return CompletableFuture.completedFuture(null); }
        };
    }

    private static DistrictsQueryApi districts(List<DistrictSummary> all) {
        return new DistrictsQueryApi() {
            public CompletableFuture<Page<DistrictSummary>> search(PagedQuery q) { return paged(all, new ArrayList<>()).apply(q); }
            public CompletableFuture<DistrictDetail> getById(int id) { return CompletableFuture.completedFuture(null); }
        };
    }

    private static StructuresQueryApi structures(List<StructureSummary> all) {
        return new StructuresQueryApi() {
            public CompletableFuture<Page<StructureSummary>> search(PagedQuery q) { return paged(all, new ArrayList<>()).apply(q); }
            public CompletableFuture<StructureDetail> getById(int id) { return CompletableFuture.completedFuture(null); }
        };
    }

    private static DomainCatalogQueryApi catalog(Function<PagedQuery, CompletableFuture<Page<KnkDomainSummary>>> search) {
        return new DomainCatalogQueryApi() {
            public CompletableFuture<Page<KnkDomainSummary>> search(PagedQuery q) { return search.apply(q); }
            public CompletableFuture<KnkDomainSummary> getById(int id) { return CompletableFuture.completedFuture(null); }
        };
    }

    private final List<TownSummary> townRows = List.of(
            new TownSummary(1, "Rivia", "", "town_1"), new TownSummary(2, "Vizima", "", "town_2"),
            new TownSummary(3, "Novigrad", "", "town_3"));
    private final List<DistrictSummary> districtRows = List.of(new DistrictSummary(4, "Old", "", "district_4", 1, "Rivia"));
    private final List<StructureSummary> structureRows = List.of(
            new StructureSummary(5, "Main gate", "", "domain_5", 1, 1, "St", 4, "Old"));

    private ManagedRegionRepairService service(FakeWorldGuard wg, TownsQueryApi townsApi, DomainCatalogQueryApi catalogApi) {
        ManagedRegionSpecSource source = new ManagedRegionSpecSource(townsApi, districts(districtRows), structures(structureRows), catalogApi, 2);
        return new ManagedRegionRepairService(source, new ManagedRegionReconciler(new ManagedRegionPolicy(), Map.of()), wg,
                List.of(), Runnable::run, Logger.getLogger("test"));
    }

    private FakeWorldGuard worldWithAllRegions() {
        FakeWorldGuard wg = new FakeWorldGuard();
        for (String id : List.of("town_1", "town_2", "town_3", "district_4", "domain_5")) {
            wg.add(id);
        }
        return wg;
    }

    @Test
    void readsEveryPageAndRepairsTheWholeHierarchy() {
        FakeWorldGuard wg = worldWithAllRegions();
        List<Integer> pages = new ArrayList<>();
        ManagedRegionRepairService service = service(wg, towns(paged(townRows, pages)),
                catalog(paged(List.of(new KnkDomainSummary(5, "Main gate", "GateStructure")), new ArrayList<>())));

        RepairReport report = service.run().join();

        assertEquals(List.of(1, 2), pages);   // 3 towns, page size 2
        assertEquals(5, report.checked());
        assertEquals(5, report.changed());
        assertEquals("town_1", wg.get("district_4").parent);
        assertEquals("district_4", wg.get("domain_5").parent);
        assertEquals(30, wg.get("domain_5").priority);
        assertTrue(service.isManaged("DOMAIN_5"));
        assertFalse(service.isManaged("spawn"));
    }

    @Test
    void runningTwiceIsSafeAndTheSecondRunChangesNothing() {
        FakeWorldGuard wg = worldWithAllRegions();
        ManagedRegionRepairService service = service(wg, towns(paged(townRows, new ArrayList<>())), null);

        service.run().join();
        RepairReport second = service.run().join();

        assertEquals(0, second.changed());
        assertEquals(5, second.unchanged());
    }

    @Test
    void anUnreachableApiFailsTheRunWithoutTouchingAnyRegion() {
        FakeWorldGuard wg = worldWithAllRegions();
        ManagedRegionRepairService service = service(wg,
                towns(query -> CompletableFuture.failedFuture(new RuntimeException("API down"))), null);

        CompletionException failure = assertThrows(CompletionException.class, () -> service.run().join());

        assertTrue(failure.getCause().getMessage().contains("API down"));
        assertEquals(0, wg.applyCalls);
        // and it can be retried afterwards
        assertEquals(5, service(wg, towns(paged(townRows, new ArrayList<>())), null).run().join().checked());
    }

    @Test
    void aFailingDomainCatalogOnlyDowngradesGatesToPlainStructures() {
        FakeWorldGuard wg = worldWithAllRegions();
        ManagedRegionRepairService service = service(wg, towns(paged(townRows, new ArrayList<>())),
                catalog(query -> CompletableFuture.failedFuture(new RuntimeException("no catalog"))));

        RepairReport report = service.run().join();

        assertEquals(0, report.failed());
        assertEquals(5, report.changed());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("domain types unavailable")));
    }
}

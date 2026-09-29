package net.knightsandkings.knk.core.regions.managed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;
import net.knightsandkings.knk.core.ports.api.DistrictsQueryApi;
import net.knightsandkings.knk.core.ports.api.DomainCatalogQueryApi;
import net.knightsandkings.knk.core.ports.api.StructuresQueryApi;
import net.knightsandkings.knk.core.ports.api.TownsQueryApi;

/** Loads every Town, District and Structure from the API (paged) and turns them into {@link DomainRegionSpecs}. */
public class ManagedRegionSpecSource {

    private static final int MAX_PAGES = 500;

    private final TownsQueryApi towns;
    private final DistrictsQueryApi districts;
    private final StructuresQueryApi structures;
    private final DomainCatalogQueryApi domainCatalog;
    private final int pageSize;

    /** @param domainCatalog optional: without it every Structure is a plain STRUCTURE (Gates not told apart). */
    public ManagedRegionSpecSource(TownsQueryApi towns, DistrictsQueryApi districts, StructuresQueryApi structures,
                                   DomainCatalogQueryApi domainCatalog, int pageSize) {
        this.towns = towns;
        this.districts = districts;
        this.structures = structures;
        this.domainCatalog = domainCatalog;
        this.pageSize = Math.max(1, pageSize);
    }

    /** Fails (exceptionally) when the towns, districts or structures cannot be read: a half-read hierarchy must not be repaired. */
    public CompletableFuture<DomainRegionSpecs.Result> load() {
        CompletableFuture<List<net.knightsandkings.knk.core.domain.towns.TownSummary>> townsFuture = fetchAll(towns::search);
        CompletableFuture<List<net.knightsandkings.knk.core.domain.districts.DistrictSummary>> districtsFuture = fetchAll(districts::search);
        CompletableFuture<List<net.knightsandkings.knk.core.domain.structures.StructureSummary>> structuresFuture = fetchAll(structures::search);
        List<String> extraWarnings = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Map<Integer, String>> typesFuture = domainCatalog == null
                ? CompletableFuture.completedFuture(Map.<Integer, String>of())
                : fetchAll(domainCatalog::search).thenApply(all -> {
                    Map<Integer, String> types = new HashMap<>();
                    for (KnkDomainSummary domain : all) {
                        if (domain.id() != null && domain.domainType() != null) {
                            types.put(domain.id(), domain.domainType());
                        }
                    }
                    return types;
                }).exceptionally(error -> {
                    extraWarnings.add("domain types unavailable (" + rootMessage(error) + "); Structures are treated as plain structures");
                    return Map.<Integer, String>of();
                });

        return CompletableFuture.allOf(townsFuture, districtsFuture, structuresFuture, typesFuture).thenApply(ignored -> {
            DomainRegionSpecs.Result built = DomainRegionSpecs.build(
                    townsFuture.join(), districtsFuture.join(), structuresFuture.join(), typesFuture.join());
            List<String> warnings = new ArrayList<>(built.warnings());
            warnings.addAll(extraWarnings);
            return new DomainRegionSpecs.Result(built.specs(), built.skipped(), warnings);
        });
    }

    private <T> CompletableFuture<List<T>> fetchAll(Function<PagedQuery, CompletableFuture<Page<T>>> search) {
        return fetchPage(search, 1, new ArrayList<>());
    }

    private <T> CompletableFuture<List<T>> fetchPage(Function<PagedQuery, CompletableFuture<Page<T>>> search, int pageNumber,
                                                     List<T> collected) {
        PagedQuery query = new PagedQuery(pageNumber, pageSize, null, null, false, Map.of());
        return search.apply(query).thenCompose(page -> {
            List<T> items = page != null && page.items() != null ? page.items() : List.of();
            collected.addAll(items);
            int total = page != null ? page.totalCount() : 0;
            if (items.isEmpty() || collected.size() >= total || pageNumber >= MAX_PAGES) {
                return CompletableFuture.completedFuture(collected);
            }
            return fetchPage(search, pageNumber + 1, collected);
        });
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }
}

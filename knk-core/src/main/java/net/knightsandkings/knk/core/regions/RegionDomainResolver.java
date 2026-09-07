package net.knightsandkings.knk.core.regions;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import net.knightsandkings.knk.core.cache.DistrictCache;
import net.knightsandkings.knk.core.cache.DomainCache;
import net.knightsandkings.knk.core.cache.StructureCache;
import net.knightsandkings.knk.core.cache.TownCache;
import net.knightsandkings.knk.core.domain.districts.DistrictDetail;
import net.knightsandkings.knk.core.domain.domains.DomainRegionQuery;
import net.knightsandkings.knk.core.domain.domains.DomainRegionSummary;
import net.knightsandkings.knk.core.domain.structures.StructureDetail;
import net.knightsandkings.knk.core.domain.towns.TownDetail;
import net.knightsandkings.knk.core.ports.api.DistrictsQueryApi;
import net.knightsandkings.knk.core.ports.api.DomainsQueryApi;
import net.knightsandkings.knk.core.ports.api.StructuresQueryApi;
import net.knightsandkings.knk.core.ports.api.TownsQueryApi;

/**
 * Resolves WorldGuard region IDs to Knights & Kings domain entities (Town, District, Structure, etc.).
 * Acts as a mapping service between WG region names and core domain models.
 *
 * Supports optional API-backed lookup using search endpoints with shared cache infrastructure.
 */
public class RegionDomainResolver {
    private static final Logger LOGGER = Logger.getLogger(RegionDomainResolver.class.getName());
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(1);

    private final TownsQueryApi townsQueryApi;
    private final DistrictsQueryApi districtsQueryApi;
    private final StructuresQueryApi structuresQueryApi;
    private final DomainsQueryApi domainsQueryApi;
    private final Duration cacheTtl;
    
    // Shared caches (optional - null if not using cache system)
    private final TownCache townCache;
    private final DistrictCache districtCache;
    private final StructureCache structureCache;

    // Local domain snapshot cache (for domain decisions, not yet in shared caches)
    private final Map<String, CachedValue<DomainSnapshot>> domainsByRegionId = new ConcurrentHashMap<>();
    private final DomainCache.CacheMetrics domainCacheMetrics = new DomainCache.CacheMetrics();

    /**
     * In-memory only (no API, no shared caches) constructor.
     */
    public RegionDomainResolver() {
        this(null, null, null, null, null, null, null);
    }

    /**
     * API-enabled constructor using default cache TTL and no shared caches.
     */
    public RegionDomainResolver(
        TownsQueryApi townsQueryApi,
        DistrictsQueryApi districtsQueryApi,
        StructuresQueryApi structuresQueryApi,
        DomainsQueryApi domainsQueryApi
    ) {
        this(townsQueryApi, districtsQueryApi, structuresQueryApi, domainsQueryApi, null, null, null);
    }

    /**
     * API-enabled constructor with shared cache infrastructure.
     * Recommended for production use.
     */
    public RegionDomainResolver(
        TownsQueryApi townsQueryApi,
        DistrictsQueryApi districtsQueryApi,
        StructuresQueryApi structuresQueryApi,
        DomainsQueryApi domainsQueryApi,
        TownCache townCache,
        DistrictCache districtCache,
        StructureCache structureCache
    ) {
        this(townsQueryApi, districtsQueryApi, structuresQueryApi, domainsQueryApi,
            townCache, districtCache, structureCache, DEFAULT_CACHE_TTL);
    }

    /**
     * Full constructor allowing an explicit cache TTL, primarily so tests can simulate a stale
     * cache entry without waiting on a real clock.
     */
    RegionDomainResolver(
        TownsQueryApi townsQueryApi,
        DistrictsQueryApi districtsQueryApi,
        StructuresQueryApi structuresQueryApi,
        DomainsQueryApi domainsQueryApi,
        TownCache townCache,
        DistrictCache districtCache,
        StructureCache structureCache,
        Duration cacheTtl
    ) {
        this.townsQueryApi = townsQueryApi;
        this.districtsQueryApi = districtsQueryApi;
        this.structuresQueryApi = structuresQueryApi;
        this.domainsQueryApi = domainsQueryApi;
        this.cacheTtl = cacheTtl;
        this.townCache = townCache;
        this.districtCache = districtCache;
        this.structureCache = structureCache;

        if (townCache != null) {
            LOGGER.info("[KnK Resolver] Initialized with shared cache infrastructure");
        }
    }

    /**
     * Resolve from the current cache only (non-blocking, main-thread safe).
     *
     * Uses getDomainByRegionIdNoRefresh (age-tolerant), matching the freshness definition
     * WorldGuardRegionTracker.checkCacheStatus already uses to decide whether to proceed
     * synchronously instead of kicking off an async re-fetch. Using the TTL-strict
     * getDomainByRegionId here instead was a real bug: once cacheTtl (1 minute) elapsed since a
     * region was first cached, this discarded the data outright (returns empty for expired
     * entries), while the tracker's own check doesn't look at age at all and so never noticed
     * anything needed refreshing - the region stayed permanently "cached but silently empty"
     * for the rest of the domain-resolution the tracker still thought was fresh, so
     * enteredDomains/leftDomains came back empty on every subsequent transition through that
     * region, forever, with no error and no retry.
     */
    public RegionSnapshot resolveRegions(Set<String> regionIds) {
        Set<DomainSnapshot> domains = new HashSet<>();
        for (String regionId : regionIds) {
            getDomainByRegionIdNoRefresh(regionId).ifPresent(domains::add);
        }
        return new RegionSnapshot(domains);
    }

    /**
     * Resolve regions, pulling missing entries from API search endpoints, caching results, then returning the snapshot.
     * Safe to run off the Paper main thread only.
     */
    public CompletableFuture<RegionSnapshot> resolveRegionsFromApi(Set<String> regionIds) {
        if (regionIds == null || regionIds.isEmpty()) {
            return CompletableFuture.completedFuture(new RegionSnapshot(Set.of()));
        }

        LOGGER.info("[KnK Resolver] resolveRegionsFromApi called for: " + regionIds);
        if (domainsQueryApi == null) {
            LOGGER.fine("[KnK Resolver] domainsQueryApi not configured; returning cache snapshot only");
            return CompletableFuture.completedFuture(resolveRegions(regionIds));
        }

        Set<String> missing = regionIds.stream()
            .filter(id -> !isCached(id))
            .collect(Collectors.toSet());

        if (missing.isEmpty()) {
            LOGGER.info("[KnK Resolver] resolveRegionsFromApi all regions cached, returning snapshot");
            return CompletableFuture.completedFuture(resolveRegions(regionIds));
        }

        DomainRegionQuery query = new DomainRegionQuery(missing, Boolean.TRUE);

        return domainsQueryApi.searchDomainRegionDecisions(query)
            .thenApply(results -> {
                registerDomainRegionSummaries(results.values());
                RegionSnapshot snapshot = resolveRegions(regionIds);
                LOGGER.info("[KnK Resolver] resolveRegionsFromApi completed: domains=" + snapshot.domains().size());
                return snapshot;
            })
            .exceptionally(ex -> {
                LOGGER.log(Level.WARNING, "Failed domain search for WG regions {0}: {1}", new Object[]{missing, ex.getMessage()});
                return resolveRegions(regionIds);
            });
    }

    private boolean isCached(String wgRegionId) {
        return getDomainByRegionId(wgRegionId).isPresent();
    }

    /**
     * Batch cache warming for multiple region IDs.
     * Efficiently preloads missing regions in a single API call.
     * Useful for server startup or when preloading common regions.
     * 
     * @param regionIds Collection of WorldGuard region IDs to warm the cache with
     * @return CompletableFuture that completes when all regions are cached
     */
    public CompletableFuture<Void> warmCache(Collection<String> regionIds) {
        if (regionIds == null || regionIds.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        Set<String> missing = regionIds.stream()
            .filter(id -> !isCached(id))
            .collect(Collectors.toSet());
        
        if (missing.isEmpty()) {
            LOGGER.fine("[KnK Resolver] warmCache: all regions already cached");
            return CompletableFuture.completedFuture(null);
        }
        
        LOGGER.info("[KnK Resolver] warmCache: preloading " + missing.size() + " regions: " + missing);
        
        if (domainsQueryApi == null) {
            LOGGER.warning("[KnK Resolver] warmCache: domainsQueryApi not configured, cannot warm cache");
            return CompletableFuture.completedFuture(null);
        }
        
        DomainRegionQuery query = new DomainRegionQuery(missing, Boolean.TRUE);
        return domainsQueryApi.searchDomainRegionDecisions(query)
            .thenAccept(results -> {
                registerDomainRegionSummaries(results.values());
                LOGGER.info("[KnK Resolver] warmCache: completed, cached " + results.size() + " domains");
            })
            .exceptionally(ex -> {
                LOGGER.log(Level.WARNING, "warmCache failed for regions: " + missing, ex);
                return null;
            });
    }

    private void registerDomainRegionSummaries(Collection<DomainRegionSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return;
        }
        Set<String> visited = new HashSet<>();
        for (DomainRegionSummary summary : summaries) {
            registerDomainHierarchy(summary, visited);
        }
    }

    private void registerDomainHierarchy(DomainRegionSummary summary, Set<String> visited) {
        if (summary == null) {
            return;
        }
        String regionId = summary.wgRegionId();
        if (regionId != null && !visited.add(regionId)) {
            return;
        }

        registerDomainFromSummary(summary);

        if (summary.parentDomainDecisions() != null) {
            for (DomainRegionSummary parent : summary.parentDomainDecisions()) {
                registerDomainHierarchy(parent, visited);
            }
        }
    }

    private void registerDomainFromSummary(DomainRegionSummary summary) {
        registerDomain(new DomainSnapshot(
            summary.id(),
            summary.name(),
            summary.description(),
            summary.wgRegionId(),
            summary.allowEntry(),
            summary.allowExit(),
            summary.domainType(),
            summary.parentDomainDecisions() == null ? Set.of() :
                summary.parentDomainDecisions().stream()
                    .map(DomainRegionSummary::id)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet()),
            summary.parentDomainDecisions() == null ? Set.of() :
                summary.parentDomainDecisions().stream()
                    .map(DomainRegionSummary::name)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet()),
            Set.of(),
            Set.of()
        ));
    }

    /**
     * Get domain from cache WITHOUT triggering background refresh.
     * Used by WorldGuardRegionTracker to avoid refresh storms.
     * Returns cached value even if expired (caller decides whether to refresh).
     * 
     * Checks shared caches first for potentially fresher data before falling back to domain snapshots.
     */
    public Optional<DomainSnapshot> getDomainByRegionIdNoRefresh(String wgRegionId) {
        // Check shared caches first - they may have fresher data
        Optional<DomainSnapshot> fromSharedCache = checkSharedCaches(wgRegionId);
        if (fromSharedCache.isPresent()) {
            domainCacheMetrics.recordHit();
            return fromSharedCache;
        }
        
        // Fall back to local domain snapshot cache
        CachedValue<DomainSnapshot> cached = domainsByRegionId.get(wgRegionId);
        if (cached == null) {
            domainCacheMetrics.recordMiss();
            return Optional.empty();
        }
        
        domainCacheMetrics.recordHit();
        return Optional.of(cached.value());
    }

    /**
     * Get domain from cache. Returns empty if not cached or expired.
     * Does NOT trigger automatic background refresh to prevent API storms.
     * 
     * Checks shared caches first for potentially fresher data.
     */
    public Optional<DomainSnapshot> getDomainByRegionId(String wgRegionId) {
        // Check shared caches first - they may have fresher data
        Optional<DomainSnapshot> fromSharedCache = checkSharedCaches(wgRegionId);
        if (fromSharedCache.isPresent()) {
            LOGGER.fine("[KnK Resolver] Domain cache HIT (shared) for: " + wgRegionId);
            domainCacheMetrics.recordHit();
            return fromSharedCache;
        }
        
        // Fall back to local domain snapshot cache
        CachedValue<DomainSnapshot> cached = domainsByRegionId.get(wgRegionId);
        if (cached == null) {
            LOGGER.fine("[KnK Resolver] Domain cache MISS for: " + wgRegionId + " (not cached)");
            domainCacheMetrics.recordMiss();
            return Optional.empty();
        }
        
        if (cached.isExpired(cacheTtl)) {
            LOGGER.fine("[KnK Resolver] Domain cache STALE for: " + wgRegionId + " -> " + cached.value().name());
            domainCacheMetrics.recordStaleHit();
            return Optional.empty(); // Return empty for expired entries
        }
        
        LOGGER.fine("[KnK Resolver] Domain cache HIT for: " + wgRegionId + " -> " + cached.value().name());
        domainCacheMetrics.recordHit();
        return Optional.of(cached.value());
    }

    public void registerDomain(DomainSnapshot domain) {
        if (domain != null && domain.wgRegionId() != null) {
            domainsByRegionId.put(domain.wgRegionId(), new CachedValue<>(domain, Instant.now()));
            domainCacheMetrics.recordPut();
        }
    }
    
    /**
     * Check shared caches for domain info by region ID.
     * Converts cached detail objects back to DomainSnapshot format.
     */
    private Optional<DomainSnapshot> checkSharedCaches(String wgRegionId) {
        if (wgRegionId == null) {
            return Optional.empty();
        }
        
        // Check town cache
        if (townCache != null) {
            Optional<TownDetail> town = townCache.getByWgRegionId(wgRegionId);
            if (town.isPresent()) {
                TownDetail t = town.get();
                return Optional.of(new DomainSnapshot(
                    t.id(), t.name(), t.description(), t.wgRegionId(),
                    t.allowEntry(), t.allowExit(), "Town",
                    Set.of(), // Parent IDs not available in TownDetail
                    Set.of(),
                    Set.of(), // Child IDs not available in TownDetail
                    Set.of()
                ));
            }
        }
        
        // Check district cache
        if (districtCache != null) {
            Optional<DistrictDetail> district = districtCache.getByWgRegionId(wgRegionId);
            if (district.isPresent()) {
                DistrictDetail d = district.get();
                return Optional.of(new DomainSnapshot(
                    d.id(), d.name(), d.description(), d.wgRegionId(),
                    d.allowEntry(), d.allowExit(), "District",
                    Set.of(), // Parent IDs not directly available
                    Set.of(),
                    Set.of(), // Child IDs not directly available
                    Set.of()
                ));
            }
        }
        
        // Check structure cache
        if (structureCache != null) {
            Optional<StructureDetail> structure = structureCache.getByWgRegionId(wgRegionId);
            if (structure.isPresent()) {
                StructureDetail s = structure.get();
                return Optional.of(new DomainSnapshot(
                    s.id(), s.name(), s.description(), s.wgRegionId(),
                    s.allowEntry(), s.allowExit(), "Structure",
                    Set.of(), // Parent IDs not directly available
                    Set.of(),
                    Set.of(), // Child IDs not directly available
                    Set.of()
                ));
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * Get cache metrics for monitoring domain snapshot cache performance.
     */
    public DomainCache.CacheMetrics getDomainCacheMetrics() {
        return domainCacheMetrics;
    }
    
    /**
     * Get the current size of the domain snapshot cache.
     */
    public int getDomainCacheSize() {
        return domainsByRegionId.size();
    }

    /**
     * Fetch and cache a town by WG region ID using POST /Towns/search then GET /Towns/{id}.
     */
    // public CompletableFuture<Optional<TownSnapshot>> fetchTownByRegionId(String wgRegionId) {
    //     if (wgRegionId == null || townsQueryApi == null) {
    //         return CompletableFuture.completedFuture(Optional.empty());
    //     }

    //     CachedValue<TownSnapshot> cached = townsByRegionId.get(wgRegionId);
    //     if (cached != null && !cached.isExpired(cacheTtl)) {
    //         LOGGER.info("[KnK Resolver] fetchTown: returning cached for " + wgRegionId);
    //         return CompletableFuture.completedFuture(Optional.of(cached.value()));
    //     }

    //     LOGGER.info("[KnK Resolver] fetchTown: calling API for " + wgRegionId);
    //     PagedQuery query = new PagedQuery(1, 1, null, null, false, Map.of("wgRegionId", wgRegionId));
    //     return townsQueryApi.search(query)
    //         .thenCompose(page -> {
    //             LOGGER.info("[KnK Resolver] fetchTown: search returned " + page.items().size() + " items for " + wgRegionId);
    //             return page.items().stream().findFirst()
    //                 .map(TownSummary::id)
    //                 .filter(Objects::nonNull)
    //                 .map(townId -> {
    //                     LOGGER.info("[KnK Resolver] fetchTown: calling getById(" + townId + ") for " + wgRegionId);
    //                     return townsQueryApi.getById(townId)
    //                         .thenApply(detail -> {
    //                             TownSnapshot snapshot = toTownSnapshot(detail);
    //                                 // If town's wgRegionId is null, cache it under the search parameter instead
    //                                 if (snapshot.wgRegionId() == null) {
    //                                     LOGGER.warning("[KnK Resolver] fetchTown: Town '" + snapshot.name() + "' (id=" + snapshot.id() + ") has NULL wgRegionId! Caching under search param=" + wgRegionId);
    //                                     TownSnapshot correctedSnapshot = new TownSnapshot(
    //                                         snapshot.id(), snapshot.name(), wgRegionId,
    //                                         snapshot.allowEntry(), snapshot.allowExit()
    //                                     );
    //                                     registerTown(correctedSnapshot);
    //                                     return correctedSnapshot;
    //                                 } else {
    //                                     registerTown(snapshot);
    //                                     LOGGER.info("[KnK Resolver] fetchTown: cached town '" + snapshot.name() + "' for param=" + wgRegionId + ", snapshot.wgRegionId=" + snapshot.wgRegionId());
    //                                 }
    //                             return snapshot;
    //                         });
    //                 })
    //                 .orElseGet(() -> {
    //                     LOGGER.info("[KnK Resolver] fetchTown: no town found for " + wgRegionId);
    //                     return CompletableFuture.completedFuture(null);
    //                 });
    //         })
    //         .handle((snapshot, ex) -> {
    //             if (ex != null) {
    //                 LOGGER.log(Level.WARNING, "Failed to resolve town for WG region {0}: {1}", new Object[]{wgRegionId, ex.getMessage()});
    //                 townsByRegionId.remove(wgRegionId);
    //                 return Optional.<TownSnapshot>empty();
    //             }
    //             return Optional.ofNullable(snapshot);
    //         });
    // }

    /**
     * Fetch and cache a district by WG region ID using POST /Districts/search then GET /Districts/{id}.
     */
    // public CompletableFuture<Optional<DistrictSnapshot>> fetchDistrictByRegionId(String wgRegionId) {
    //     if (wgRegionId == null || districtsQueryApi == null) {
    //         return CompletableFuture.completedFuture(Optional.empty());
    //     }

    //     CachedValue<DistrictSnapshot> cached = districtsByRegionId.get(wgRegionId);
    //     if (cached != null && !cached.isExpired(cacheTtl)) {
    //         LOGGER.info("[KnK Resolver] fetchDistrict: returning cached for " + wgRegionId);
    //         return CompletableFuture.completedFuture(Optional.of(cached.value()));
    //     }

    //     LOGGER.info("[KnK Resolver] fetchDistrict: calling API for " + wgRegionId);
    //     PagedQuery query = new PagedQuery(1, 1, null, null, false, Map.of("wgRegionId", wgRegionId));
    //     return districtsQueryApi.search(query)
    //         .thenCompose(page -> {
    //             LOGGER.info("[KnK Resolver] fetchDistrict: search returned " + page.items().size() + " items for " + wgRegionId);
    //             return page.items().stream().findFirst()
    //                 .map(DistrictSummary::id)
    //                 .filter(Objects::nonNull)
    //                 .map(districtId -> {
    //                     LOGGER.info("[KnK Resolver] fetchDistrict: calling getById(" + districtId + ") for " + wgRegionId);
    //                     return districtsQueryApi.getById(districtId)
    //                         .thenApply(detail -> {
    //                             DistrictSnapshot snapshot = toDistrictSnapshot(detail);
    //                                 // If district's wgRegionId is null, cache it under the search parameter instead
    //                                 if (snapshot.wgRegionId() == null) {
    //                                     LOGGER.warning("[KnK Resolver] fetchDistrict: District '" + snapshot.name() + "' (id=" + snapshot.id() + ") has NULL wgRegionId! Caching under search param=" + wgRegionId);
    //                                     DistrictSnapshot correctedSnapshot = new DistrictSnapshot(
    //                                         snapshot.id(), snapshot.name(), wgRegionId, snapshot.townId(), 
    //                                         snapshot.allowEntry(), snapshot.allowExit()
    //                                     );
    //                                     registerDistrict(correctedSnapshot);
    //                                     return correctedSnapshot;
    //                                 } else {
    //                                     registerDistrict(snapshot);
    //                                     LOGGER.info("[KnK Resolver] fetchDistrict: cached district '" + snapshot.name() + "' for param=" + wgRegionId + ", snapshot.wgRegionId=" + snapshot.wgRegionId());
    //                                 }
    //                             return snapshot;
    //                         });
    //                 })
    //                 .orElseGet(() -> {
    //                     LOGGER.info("[KnK Resolver] fetchDistrict: no district found for " + wgRegionId);
    //                     return CompletableFuture.completedFuture(null);
    //                 });
    //         })
    //         .handle((snapshot, ex) -> {
    //             if (ex != null) {
    //                 LOGGER.log(Level.WARNING, "Failed to resolve district for WG region {0}: {1}", new Object[]{wgRegionId, ex.getMessage()});
    //                 districtsByRegionId.remove(wgRegionId);
    //                 return Optional.<DistrictSnapshot>empty();
    //             }
    //             return Optional.ofNullable(snapshot);
    //         });
    // }

    /**
     * Fetch and cache a structure by WG region ID using POST /Structures/search then GET /Structures/{id}.
     */
    // public CompletableFuture<Optional<StructureSnapshot>> fetchStructureByRegionId(String wgRegionId) {
    //     if (wgRegionId == null || structuresQueryApi == null) {
    //         return CompletableFuture.completedFuture(Optional.empty());
    //     }

    //     CachedValue<StructureSnapshot> cached = structuresByRegionId.get(wgRegionId);
    //     if (cached != null && !cached.isExpired(cacheTtl)) {
    //         return CompletableFuture.completedFuture(Optional.of(cached.value()));
    //     }

    //     PagedQuery query = new PagedQuery(1, 1, null, null, false, Map.of("wgRegionId", wgRegionId));
    //     return structuresQueryApi.search(query)
    //         .thenCompose(page -> page.items().stream().findFirst()
    //             .map(StructureSummary::id)
    //             .filter(Objects::nonNull)
    //             .map(structureId -> structuresQueryApi.getById(structureId)
    //                 .thenApply(detail -> {
    //                     StructureSnapshot snapshot = toStructureSnapshot(detail);
    //                         // If structure's wgRegionId is null, cache it under the search parameter instead
    //                         if (snapshot.wgRegionId() == null) {
    //                             LOGGER.warning("[KnK Resolver] fetchStructure: Structure '" + snapshot.name() + "' (id=" + snapshot.id() + ") has NULL wgRegionId! Caching under search param=" + wgRegionId);
    //                             StructureSnapshot correctedSnapshot = new StructureSnapshot(
    //                                 snapshot.id(), snapshot.name(), wgRegionId, snapshot.districtId(),
    //                                 snapshot.townId(), snapshot.allowEntry(), snapshot.allowExit(), snapshot.isGate()
    //                             );
    //                             registerStructure(correctedSnapshot);
    //                             return correctedSnapshot;
    //                         } else {
    //                             registerStructure(snapshot);
    //                         }
    //                     return snapshot;
    //                 }))
    //             .orElseGet(() -> CompletableFuture.completedFuture(null)))
    //         .handle((snapshot, ex) -> {
    //             if (ex != null) {
    //                 LOGGER.log(Level.WARNING, "Failed to resolve structure for WG region {0}: {1}", new Object[]{wgRegionId, ex.getMessage()});
    //                 structuresByRegionId.remove(wgRegionId);
    //                 return Optional.<StructureSnapshot>empty();
    //             }
    //             return Optional.ofNullable(snapshot);
    //         });
    // }

    private StructureSnapshot toStructureSnapshot(StructureDetail detail) {
        if (detail == null) return null;
        // TODO: Add gate detection once exposed by API schema
        return new StructureSnapshot(
            detail.id(),
            detail.name(),
            detail.wgRegionId(),
            detail.districtId(),
            null,
            detail.allowEntry(),
            detail.allowExit(),
            false
        );
    }

    public record RegionSnapshot(
        Set<DomainSnapshot> domains
    ) {}

    public record DomainSnapshot(
        Integer id,
        String name,
        String description,
        String wgRegionId,
        Boolean allowEntry,
        Boolean allowExit,
        String domainType,
        Set<Integer> parentDomainIds,
        Set<String> parentDomainNames,
        Set<Integer> childDomainIds,
        Set<String> childDomainNames
    ) {}

    public record StructureSnapshot(
        Integer id,
        String name,
        String wgRegionId,
        Integer districtId,
        Integer townId,
        Boolean allowEntry,
        Boolean allowExit,
        Boolean isGate
    ) {}

    private record CachedValue<T>(T value, Instant cachedAt) {
        boolean isExpired(Duration ttl) {
            return cachedAt.plus(ttl).isBefore(Instant.now());
        }
    }
}

package net.knightsandkings.knk.core.dataaccess;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.cache.DistrictCache;
import net.knightsandkings.knk.core.domain.districts.DistrictDetail;
import net.knightsandkings.knk.core.ports.api.DistrictsQueryApi;

/**
 * Data access gateway for Districts.
 * <p>
 * Provides cache-first, API-fallback retrieval methods with support for
 * fetch policies, stale reads, invalidation, and background refresh.
 * <p>
 * Thread-safe: All public methods are async and do not block the calling thread.
 */
public class DistrictsDataAccess {
    
    private static final Logger LOGGER = Logger.getLogger(DistrictsDataAccess.class.getName());
    
    private final DistrictCache districtCache;
    private final DistrictsQueryApi districtsQueryApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<Integer, DistrictDetail> executor;
    
    /**
     * Create a new DistrictsDataAccess gateway.
     *
     * @param districtCache The district cache instance
     * @param districtsQueryApi The districts query API port
     */
    public DistrictsDataAccess(
        DistrictCache districtCache,
        DistrictsQueryApi districtsQueryApi
    ) {
        this(districtCache, districtsQueryApi, DataAccessSettings.defaults());
    }

    public DistrictsDataAccess(
        DistrictCache districtCache,
        DistrictsQueryApi districtsQueryApi,
        DataAccessSettings settings
    ) {
        this.districtCache = Objects.requireNonNull(districtCache, "districtCache must not be null");
        this.districtsQueryApi = Objects.requireNonNull(districtsQueryApi, "districtsQueryApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(districtCache, this.settings, "District");
    }
    
    /**
     * Retrieve a district by ID using the specified fetch policy.
     * <p>
     * Async method; safe for event threads. Do not block on the result
     * in sync contexts; use background handlers or listener continuations.
     *
     * @param id The district ID
     * @param policy The fetch policy (defaults to CACHE_FIRST if null)
     * @return CompletableFuture resolving to FetchResult<DistrictDetail>
     */
    public CompletableFuture<FetchResult<DistrictDetail>> getByIdAsync(
        int id,
        FetchPolicy policy
    ) {
        policy = settings.resolvePolicy(policy);
        
        return executor.fetchAsync(
            id,
            policy,
            () -> districtsQueryApi.getById(id).thenApply(districtDetail -> {
                if (districtDetail != null) {
                    districtCache.put(districtDetail);
                }
                return districtDetail;
            })
        );
    }
    
    /**
     * Retrieve a district by ID using CACHE_FIRST policy (default).
     * <p>
     * Convenience method equivalent to getByIdAsync(id, CACHE_FIRST).
     *
     * @param id The district ID
     * @return CompletableFuture resolving to FetchResult<DistrictDetail>
     */
    public CompletableFuture<FetchResult<DistrictDetail>> getByIdAsync(int id) {
        return getByIdAsync(id, null);
    }
    
    /**
     * Retrieve a district by WorldGuard region ID (cache-only).
     * <p>
     * Note: This only checks cache. API lookup by WG region ID is not supported.
     *
     * @param wgRegionId The WorldGuard region ID
     * @return CompletableFuture resolving to FetchResult<DistrictDetail>
     */
    public CompletableFuture<FetchResult<DistrictDetail>> getByWgRegionIdAsync(String wgRegionId) {
        Objects.requireNonNull(wgRegionId, "wgRegionId must not be null");
        
        // WG region cache lookups are cache-only (no API endpoint for this)
        return CompletableFuture.supplyAsync(() -> {
            return districtCache.getByWgRegionId(wgRegionId)
                .map(FetchResult::<DistrictDetail>hit)
                .orElse(FetchResult.<DistrictDetail>notFound());
        });
    }
    
    /**
     * Refresh district data from the API, bypassing cache.
     * <p>
     * Fetches fresh data from the API and updates the cache.
     *
     * @param id The district ID
     * @return CompletableFuture resolving to FetchResult<DistrictDetail>
     */
    public CompletableFuture<FetchResult<DistrictDetail>> refreshAsync(int id) {
        return executor.fetchAsync(
            id,
            settings.resolvePolicy(FetchPolicy.API_ONLY),
            () -> districtsQueryApi.getById(id).thenApply(districtDetail -> {
                if (districtDetail != null) {
                    districtCache.put(districtDetail);
                }
                return districtDetail;
            })
        );
    }
    
    /**
     * Invalidate a cached district by ID.
     *
     * @param id The district ID
     */
    public void invalidate(int id) {
        districtCache.invalidate(id);
    }
    
    /**
     * Invalidate all cached districts.
     */
    public void invalidateAll() {
        districtCache.clear();
    }
}

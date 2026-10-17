package net.knightsandkings.knk.core.dataaccess;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.cache.TownCache;
import net.knightsandkings.knk.core.domain.towns.TownDetail;
import net.knightsandkings.knk.core.ports.api.TownsQueryApi;

/**
 * Data access gateway for Towns.
 * <p>
 * Provides cache-first, API-fallback retrieval methods with support for
 * fetch policies, stale reads, invalidation, and background refresh.
 * <p>
 * Thread-safe: All public methods are async and do not block the calling thread.
 */
public class TownsDataAccess {
    
    private static final Logger LOGGER = Logger.getLogger(TownsDataAccess.class.getName());
    
    private final TownCache townCache;
    private final TownsQueryApi townsQueryApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<Integer, TownDetail> executor;
    
    /**
     * Create a new TownsDataAccess gateway.
     *
     * @param townCache The town cache instance
     * @param townsQueryApi The towns query API port
     */
    public TownsDataAccess(
        TownCache townCache,
        TownsQueryApi townsQueryApi
    ) {
        this(townCache, townsQueryApi, DataAccessSettings.defaults());
    }

    public TownsDataAccess(
        TownCache townCache,
        TownsQueryApi townsQueryApi,
        DataAccessSettings settings
    ) {
        this.townCache = Objects.requireNonNull(townCache, "townCache must not be null");
        this.townsQueryApi = Objects.requireNonNull(townsQueryApi, "townsQueryApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(townCache, this.settings, "Town");
    }
    
    /**
     * Retrieve a town by ID using the specified fetch policy.
     * <p>
     * Async method; safe for event threads. Do not block on the result
     * in sync contexts; use background handlers or listener continuations.
     *
     * @param id The town ID
     * @param policy The fetch policy (defaults to CACHE_FIRST if null)
     * @return CompletableFuture resolving to FetchResult<TownDetail>
     */
    public CompletableFuture<FetchResult<TownDetail>> getByIdAsync(
        int id,
        FetchPolicy policy
    ) {
        policy = settings.resolvePolicy(policy);
        
        return executor.fetchAsync(
            id,
            policy,
            () -> townsQueryApi.getById(id).thenApply(townDetail -> {
                if (townDetail != null) {
                    townCache.put(townDetail);
                }
                return townDetail;
            })
        );
    }
    
    /**
     * Retrieve a town by ID using CACHE_FIRST policy (default).
     * <p>
     * Convenience method equivalent to getByIdAsync(id, CACHE_FIRST).
     *
     * @param id The town ID
     * @return CompletableFuture resolving to FetchResult<TownDetail>
     */
    public CompletableFuture<FetchResult<TownDetail>> getByIdAsync(int id) {
        return getByIdAsync(id, null);
    }
    
    /**
     * Retrieve a town by WorldGuard region ID (cache-only).
     * <p>
     * Note: This only checks cache. API lookup by WG region ID is not supported.
     *
     * @param wgRegionId The WorldGuard region ID
     * @return CompletableFuture resolving to FetchResult<TownDetail>
     */
    public CompletableFuture<FetchResult<TownDetail>> getByWgRegionIdAsync(String wgRegionId) {
        Objects.requireNonNull(wgRegionId, "wgRegionId must not be null");
        
        // WG region cache lookups are cache-only (no API endpoint for this)
        return CompletableFuture.supplyAsync(() -> {
            return townCache.getByWgRegionId(wgRegionId)
                .map(FetchResult::<TownDetail>hit)
                .orElse(FetchResult.<TownDetail>notFound());
        });
    }
    
    /**
     * Refresh town data from the API, bypassing cache.
     * <p>
     * Fetches fresh data from the API and updates the cache.
     *
     * @param id The town ID
     * @return CompletableFuture resolving to FetchResult<TownDetail>
     */
    public CompletableFuture<FetchResult<TownDetail>> refreshAsync(int id) {
        return executor.fetchAsync(
            id,
            settings.resolvePolicy(FetchPolicy.API_ONLY),
            () -> townsQueryApi.getById(id).thenApply(townDetail -> {
                if (townDetail != null) {
                    townCache.put(townDetail);
                }
                return townDetail;
            })
        );
    }
    
    /**
     * Invalidate a cached town by ID.
     *
     * @param id The town ID
     */
    public void invalidate(int id) {
        townCache.invalidate(id);
    }
    
    /**
     * Invalidate all cached towns.
     */
    public void invalidateAll() {
        townCache.clear();
    }
}

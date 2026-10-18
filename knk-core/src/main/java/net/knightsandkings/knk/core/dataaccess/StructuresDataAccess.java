package net.knightsandkings.knk.core.dataaccess;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.cache.StructureCache;
import net.knightsandkings.knk.core.domain.structures.StructureDetail;
import net.knightsandkings.knk.core.ports.api.StructuresQueryApi;

/**
 * Data access gateway for Structures.
 * <p>
 * Provides cache-first, API-fallback retrieval methods with support for
 * fetch policies, stale reads, invalidation, and background refresh.
 * <p>
 * Thread-safe: All public methods are async and do not block the calling thread.
 */
public class StructuresDataAccess {
    
    private static final Logger LOGGER = Logger.getLogger(StructuresDataAccess.class.getName());
    
    private final StructureCache structureCache;
    private final StructuresQueryApi structuresQueryApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<Integer, StructureDetail> executor;
    
    /**
     * Create a new StructuresDataAccess gateway.
     *
     * @param structureCache The structure cache instance
     * @param structuresQueryApi The structures query API port
     */
    public StructuresDataAccess(
        StructureCache structureCache,
        StructuresQueryApi structuresQueryApi
    ) {
        this(structureCache, structuresQueryApi, DataAccessSettings.defaults());
    }

    public StructuresDataAccess(
        StructureCache structureCache,
        StructuresQueryApi structuresQueryApi,
        DataAccessSettings settings
    ) {
        this.structureCache = Objects.requireNonNull(structureCache, "structureCache must not be null");
        this.structuresQueryApi = Objects.requireNonNull(structuresQueryApi, "structuresQueryApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(structureCache, this.settings, "Structure");
    }
    
    /**
     * Retrieve a structure by ID using the specified fetch policy.
     * <p>
     * Async method; safe for event threads. Do not block on the result
     * in sync contexts; use background handlers or listener continuations.
     *
     * @param id The structure ID
     * @param policy The fetch policy (defaults to CACHE_FIRST if null)
     * @return CompletableFuture resolving to FetchResult<StructureDetail>
     */
    public CompletableFuture<FetchResult<StructureDetail>> getByIdAsync(
        int id,
        FetchPolicy policy
    ) {
        policy = settings.resolvePolicy(policy);
        
        return executor.fetchAsync(
            id,
            policy,
            () -> structuresQueryApi.getById(id).thenApply(structureDetail -> {
                if (structureDetail != null) {
                    structureCache.put(structureDetail);
                }
                return structureDetail;
            })
        );
    }
    
    /**
     * Retrieve a structure by ID using CACHE_FIRST policy (default).
     * <p>
     * Convenience method equivalent to getByIdAsync(id, CACHE_FIRST).
     *
     * @param id The structure ID
     * @return CompletableFuture resolving to FetchResult<StructureDetail>
     */
    public CompletableFuture<FetchResult<StructureDetail>> getByIdAsync(int id) {
        return getByIdAsync(id, null);
    }
    
    /**
     * Retrieve a structure by WorldGuard region ID (cache-only).
     * <p>
     * Note: This only checks cache. API lookup by WG region ID is not supported.
     *
     * @param wgRegionId The WorldGuard region ID
     * @return CompletableFuture resolving to FetchResult<StructureDetail>
     */
    public CompletableFuture<FetchResult<StructureDetail>> getByWgRegionIdAsync(String wgRegionId) {
        Objects.requireNonNull(wgRegionId, "wgRegionId must not be null");
        
        // WG region cache lookups are cache-only (no API endpoint for this)
        return CompletableFuture.supplyAsync(() -> {
            return structureCache.getByWgRegionId(wgRegionId)
                .map(FetchResult::<StructureDetail>hit)
                .orElse(FetchResult.<StructureDetail>notFound());
        });
    }
    
    /**
     * Refresh structure data from the API, bypassing cache.
     * <p>
     * Fetches fresh data from the API and updates the cache.
     *
     * @param id The structure ID
     * @return CompletableFuture resolving to FetchResult<StructureDetail>
     */
    public CompletableFuture<FetchResult<StructureDetail>> refreshAsync(int id) {
        return executor.fetchAsync(
            id,
            settings.resolvePolicy(FetchPolicy.API_ONLY),
            () -> structuresQueryApi.getById(id).thenApply(structureDetail -> {
                if (structureDetail != null) {
                    structureCache.put(structureDetail);
                }
                return structureDetail;
            })
        );
    }
    
    /**
     * Invalidate a cached structure by ID.
     *
     * @param id The structure ID
     */
    public void invalidate(int id) {
        structureCache.invalidate(id);
    }
    
    /**
     * Invalidate all cached structures.
     */
    public void invalidateAll() {
        structureCache.clear();
    }
}

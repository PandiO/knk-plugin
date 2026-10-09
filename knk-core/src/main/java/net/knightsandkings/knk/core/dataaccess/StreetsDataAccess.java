package net.knightsandkings.knk.core.dataaccess;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.cache.StreetCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.streets.StreetDetail;
import net.knightsandkings.knk.core.domain.streets.StreetSummary;
import net.knightsandkings.knk.core.ports.api.StreetsQueryApi;

/**
 * Data access gateway for Streets.
 * <p>
 * Provides cache-first, API-fallback retrieval methods with support for
 * fetch policies, stale reads, invalidation, and background refresh.
 * <p>
 * Thread-safe: All public methods are async and do not block the calling thread.
 * <p>
 * READ-ONLY: Streets do not support create/update operations.
 */
public class StreetsDataAccess {
    
    private static final Logger LOGGER = Logger.getLogger(StreetsDataAccess.class.getName());
    
    private final StreetCache streetCache;
    private final StreetsQueryApi streetsQueryApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<Integer, StreetDetail> executor;
    
    /**
     * Create a new StreetsDataAccess gateway.
     *
     * @param streetCache The street cache instance
     * @param streetsQueryApi The streets query API port
     */
    public StreetsDataAccess(
        StreetCache streetCache,
        StreetsQueryApi streetsQueryApi
    ) {
        this(streetCache, streetsQueryApi, DataAccessSettings.defaults());
    }

    public StreetsDataAccess(
        StreetCache streetCache,
        StreetsQueryApi streetsQueryApi,
        DataAccessSettings settings
    ) {
        this.streetCache = Objects.requireNonNull(streetCache, "streetCache must not be null");
        this.streetsQueryApi = Objects.requireNonNull(streetsQueryApi, "streetsQueryApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(streetCache, this.settings.retryPolicy(), "Street");
    }
    
    /**
     * Retrieve a street by ID using the specified fetch policy.
     * <p>
     * Async method; safe for event threads. Do not block on the result
     * in sync contexts; use background handlers or listener continuations.
     *
     * @param id The street ID
     * @param policy The fetch policy (defaults to CACHE_FIRST if null)
     * @return CompletableFuture resolving to FetchResult<StreetDetail>
     */
    public CompletableFuture<FetchResult<StreetDetail>> getByIdAsync(
        int id,
        FetchPolicy policy
    ) {
        policy = settings.resolvePolicy(policy);
        
        return executor.fetchAsync(
            id,
            policy,
            () -> streetsQueryApi.getById(id).thenApply(streetDetail -> {
                if (streetDetail != null) {
                    streetCache.put(streetDetail);
                }
                return streetDetail;
            })
        );
    }
    
    /**
     * Retrieve a street by ID using CACHE_FIRST policy (default).
     * <p>
     * Convenience method equivalent to getByIdAsync(id, CACHE_FIRST).
     *
     * @param id The street ID
     * @return CompletableFuture resolving to FetchResult<StreetDetail>
     */
    public CompletableFuture<FetchResult<StreetDetail>> getByIdAsync(int id) {
        return getByIdAsync(id, null);
    }
    
    /**
     * Refresh street data from the API, bypassing cache.
     * <p>
     * Fetches fresh data from the API and updates the cache.
     *
     * @param id The street ID
     * @return CompletableFuture resolving to FetchResult<StreetDetail>
     */
    public CompletableFuture<FetchResult<StreetDetail>> refreshAsync(int id) {
        return executor.fetchAsync(
            id,
            settings.resolvePolicy(FetchPolicy.API_ONLY),
            () -> streetsQueryApi.getById(id).thenApply(streetDetail -> {
                if (streetDetail != null) {
                    streetCache.put(streetDetail);
                }
                return streetDetail;
            })
        );
    }
    
    /**
     * Search streets by name (road navigation plan §2 R18). Straight to the API
     * ({@code POST /api/Streets/search}); the summaries carry no detail to cache.
     *
     * @param query paged query with search term, filters, sort
     * @return CompletableFuture resolving to the page of summaries
     */
    public CompletableFuture<Page<StreetSummary>> searchAsync(PagedQuery query) {
        return streetsQueryApi.search(query);
    }

    /**
     * Invalidate a cached street by ID.
     *
     * @param id The street ID
     */
    public void invalidate(int id) {
        streetCache.invalidate(id);
    }
    
    /**
     * Invalidate all cached streets.
     */
    public void invalidateAll() {
        streetCache.clear();
    }
}

package net.knightsandkings.knk.core.dataaccess;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.ports.api.LocationsQueryApi;

import java.time.Duration;

/**
 * Data access gateway for Locations.
 * <p>
 * Provides cache-first, API-fallback retrieval methods with support for
 * fetch policies, stale reads, invalidation, and background refresh.
 * <p>
 * Thread-safe: All public methods are async and do not block the calling thread.
 */
public class LocationsDataAccess {
    
    private static final Logger LOGGER = Logger.getLogger(LocationsDataAccess.class.getName());
    
    private final LocationCache locationCache;
    private final LocationsQueryApi locationsQueryApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<Integer, KnkLocation> executor;
    
    /**
     * Simple cache for locations - inline implementation.
     */
    private static class LocationCache extends BaseCache<Integer, KnkLocation> {
        public LocationCache(Duration ttl) {
            super(ttl);
        }
        
        public void put(KnkLocation location) {
            if (location != null && location.id() != null) {
                put(location.id(), location);
            }
        }
    }
    
    /**
     * Create a new LocationsDataAccess gateway.
     *
     * @param ttl Cache time-to-live duration
     * @param locationsQueryApi The locations query API port
     */
    public LocationsDataAccess(
        Duration ttl,
        LocationsQueryApi locationsQueryApi
    ) {
        this(ttl, locationsQueryApi, DataAccessSettings.defaults());
    }

    public LocationsDataAccess(
        Duration ttl,
        LocationsQueryApi locationsQueryApi,
        DataAccessSettings settings
    ) {
        this.locationCache = new LocationCache(ttl);
        this.locationsQueryApi = Objects.requireNonNull(locationsQueryApi, "locationsQueryApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(locationCache, this.settings.retryPolicy(), "Location");
    }
    
    /**
     * Retrieve a location by ID using the specified fetch policy.
     * <p>
     * Async method; safe for event threads. Do not block on the result
     * in sync contexts; use background handlers or listener continuations.
     *
     * @param id The location ID
     * @param policy The fetch policy (defaults to CACHE_FIRST if null)
     * @return CompletableFuture resolving to FetchResult<KnkLocation>
     */
    public CompletableFuture<FetchResult<KnkLocation>> getByIdAsync(
        int id,
        FetchPolicy policy
    ) {
        policy = settings.resolvePolicy(policy);
        
        return executor.fetchAsync(
            id,
            policy,
            () -> locationsQueryApi.getById(id).thenApply(location -> {
                if (location != null) {
                    locationCache.put(location);
                }
                return location;
            })
        );
    }
    
    /**
     * Retrieve a location by ID using CACHE_FIRST policy (default).
     * <p>
     * Convenience method equivalent to getByIdAsync(id, CACHE_FIRST).
     *
     * @param id The location ID
     * @return CompletableFuture resolving to FetchResult<KnkLocation>
     */
    public CompletableFuture<FetchResult<KnkLocation>> getByIdAsync(int id) {
        return getByIdAsync(id, null);
    }
    
    /**
     * Refresh location data from the API, bypassing cache.
     * <p>
     * Fetches fresh data from the API and updates the cache.
     *
     * @param id The location ID
     * @return CompletableFuture resolving to FetchResult<KnkLocation>
     */
    public CompletableFuture<FetchResult<KnkLocation>> refreshAsync(int id) {
        return executor.fetchAsync(
            id,
            settings.resolvePolicy(FetchPolicy.API_ONLY),
            () -> locationsQueryApi.getById(id).thenApply(location -> {
                if (location != null) {
                    locationCache.put(location);
                }
                return location;
            })
        );
    }
    
    /**
     * Search locations (road navigation plan §2 R18: the {@code /navigate} catalogue). Straight to
     * the API ({@code POST /api/Locations/search}); every location returned is cached by id.
     *
     * @param query paged query with search term, filters, sort
     * @return CompletableFuture resolving to the page
     */
    public CompletableFuture<Page<KnkLocation>> searchAsync(PagedQuery query) {
        return locationsQueryApi.search(query).thenApply(page -> {
            if (page != null && page.items() != null) {
                for (KnkLocation location : page.items()) {
                    if (location != null && location.id() != null) {
                        locationCache.put(location);
                    }
                }
            }
            return page;
        });
    }

    /**
     * Invalidate a cached location by ID.
     *
     * @param id The location ID
     */
    public void invalidate(int id) {
        locationCache.invalidate(id);
    }
    
    /**
     * Invalidate all cached locations.
     */
    public void invalidateAll() {
        locationCache.clear();
    }
}

package net.knightsandkings.knk.core.dataaccess;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.HealthStatus;
import net.knightsandkings.knk.core.ports.api.HealthApi;

import java.time.Duration;

/**
 * Data access gateway for Health status.
 * <p>
 * Provides minimal caching for health checks with very short TTL.
 * Health checks are typically fast and should be checked frequently.
 * <p>
 * Thread-safe: All public methods are async and do not block the calling thread.
 * <p>
 * Not a reachability signal (KNG-115): a cached "healthy" says nothing about whether the API
 * answers now. For up/down decisions use the plugin's {@code ApiConnectivity} state, which is fed
 * by direct probes; use {@link #refreshAsync()} or {@code FetchPolicy.API_ONLY} here if a fresh
 * reading is needed.
 */
public class HealthDataAccess {
    
    private static final Logger LOGGER = Logger.getLogger(HealthDataAccess.class.getName());
    private static final String HEALTH_KEY = "api-health";
    
    private final HealthCache healthCache;
    private final HealthApi healthApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<String, HealthStatus> executor;
    
    /**
     * Simple cache for health status - single entry keyed by constant.
     */
    private static class HealthCache extends BaseCache<String, HealthStatus> {
        public HealthCache(Duration ttl) {
            super(ttl);
        }
        
        public void put(HealthStatus status) {
            if (status != null) {
                put(HEALTH_KEY, status);
            }
        }
    }
    
    /**
     * Create a new HealthDataAccess gateway.
     *
     * @param ttl Cache time-to-live duration (recommended: 30 seconds)
     * @param healthApi The health API port
     */
    public HealthDataAccess(
        Duration ttl,
        HealthApi healthApi
    ) {
        this(ttl, healthApi, DataAccessSettings.defaults());
    }

    public HealthDataAccess(
        Duration ttl,
        HealthApi healthApi,
        DataAccessSettings settings
    ) {
        this.healthCache = new HealthCache(ttl);
        this.healthApi = Objects.requireNonNull(healthApi, "healthApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(healthCache, this.settings.retryPolicy(), "Health");
    }
    
    /**
     * Get health status using the specified fetch policy.
     * <p>
     * Async method; safe for event threads. Do not block on the result
     * in sync contexts; use background handlers or listener continuations.
     *
     * @param policy The fetch policy (defaults to CACHE_FIRST if null)
     * @return CompletableFuture resolving to FetchResult<HealthStatus>
     */
    public CompletableFuture<FetchResult<HealthStatus>> getHealthAsync(FetchPolicy policy) {
        policy = settings.resolvePolicy(policy);
        
        return executor.fetchAsync(
            HEALTH_KEY,
            policy,
            () -> healthApi.getHealth().thenApply(health -> {
                if (health != null) {
                    healthCache.put(health);
                }
                return health;
            })
        );
    }
    
    /**
     * Get health status using CACHE_FIRST policy (default).
     * <p>
     * Convenience method equivalent to getHealthAsync(CACHE_FIRST).
     *
     * @return CompletableFuture resolving to FetchResult<HealthStatus>
     */
    public CompletableFuture<FetchResult<HealthStatus>> getHealthAsync() {
        return getHealthAsync(null);
    }
    
    /**
     * Refresh health status from the API, bypassing cache.
     * <p>
     * Fetches fresh data from the API and updates the cache.
     *
     * @return CompletableFuture resolving to FetchResult<HealthStatus>
     */
    public CompletableFuture<FetchResult<HealthStatus>> refreshAsync() {
        return executor.fetchAsync(
            HEALTH_KEY,
            settings.resolvePolicy(FetchPolicy.API_ONLY),
            () -> healthApi.getHealth().thenApply(health -> {
                if (health != null) {
                    healthCache.put(health);
                }
                return health;
            })
        );
    }
    
    /**
     * Invalidate cached health status.
     */
    public void invalidate() {
        healthCache.invalidate(HEALTH_KEY);
    }
    
    /**
     * Invalidate all cached health data (same as invalidate() for health).
     */
    public void invalidateAll() {
        healthCache.clear();
    }
}

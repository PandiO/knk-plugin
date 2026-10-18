package net.knightsandkings.knk.core.dataaccess;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.domains.DomainRegionSummary;
import net.knightsandkings.knk.core.ports.api.DomainsQueryApi;

import java.time.Duration;

/**
 * Data access gateway for Domains.
 * <p>
 * Provides cache-first, API-fallback retrieval methods with support for
 * fetch policies, stale reads, invalidation, and background refresh.
 * <p>
 * Thread-safe: All public methods are async and do not block the calling thread.
 * <p>
 * Note: Domains are primarily accessed by WorldGuard region ID.
 */
public class DomainsDataAccess {
    
    private static final Logger LOGGER = Logger.getLogger(DomainsDataAccess.class.getName());
    
    private final DomainCache domainCache;
    private final DomainsQueryApi domainsQueryApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<String, DomainRegionSummary> executor;
    
    /**
     * Simple cache for domains - keyed by WG region ID.
     */
    private static class DomainCache extends BaseCache<String, DomainRegionSummary> {
        public DomainCache(Duration ttl) {
            super(ttl);
        }
        
        public void put(DomainRegionSummary domain) {
            if (domain != null && domain.wgRegionId() != null) {
                put(domain.wgRegionId(), domain);
            }
        }
    }
    
    /**
     * Create a new DomainsDataAccess gateway.
     *
     * @param ttl Cache time-to-live duration
     * @param domainsQueryApi The domains query API port
     */
    public DomainsDataAccess(
        Duration ttl,
        DomainsQueryApi domainsQueryApi
    ) {
        this(ttl, domainsQueryApi, DataAccessSettings.defaults());
    }

    public DomainsDataAccess(
        Duration ttl,
        DomainsQueryApi domainsQueryApi,
        DataAccessSettings settings
    ) {
        this.domainCache = new DomainCache(ttl);
        this.domainsQueryApi = Objects.requireNonNull(domainsQueryApi, "domainsQueryApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(domainCache, this.settings, "Domain");
    }
    
    /**
     * Retrieve a domain by WorldGuard region ID using the specified fetch policy.
     * <p>
     * Async method; safe for event threads. Do not block on the result
     * in sync contexts; use background handlers or listener continuations.
     *
     * @param wgRegionId The WorldGuard region ID
     * @param policy The fetch policy (defaults to CACHE_FIRST if null)
     * @return CompletableFuture resolving to FetchResult<DomainRegionSummary>
     */
    public CompletableFuture<FetchResult<DomainRegionSummary>> getByWgRegionIdAsync(
        String wgRegionId,
        FetchPolicy policy
    ) {
        Objects.requireNonNull(wgRegionId, "wgRegionId must not be null");
        policy = settings.resolvePolicy(policy);
        
        return executor.fetchAsync(
            wgRegionId,
            policy,
            () -> domainsQueryApi.getByWorldGuardRegionId(wgRegionId).thenApply(domain -> {
                if (domain != null) {
                    domainCache.put(domain);
                }
                return domain;
            })
        );
    }
    
    /**
     * Retrieve a domain by WorldGuard region ID using CACHE_FIRST policy (default).
     * <p>
     * Convenience method equivalent to getByWgRegionIdAsync(wgRegionId, CACHE_FIRST).
     *
     * @param wgRegionId The WorldGuard region ID
     * @return CompletableFuture resolving to FetchResult<DomainRegionSummary>
     */
    public CompletableFuture<FetchResult<DomainRegionSummary>> getByWgRegionIdAsync(String wgRegionId) {
        return getByWgRegionIdAsync(wgRegionId, null);
    }
    
    /**
     * Refresh domain data from the API, bypassing cache.
     * <p>
     * Fetches fresh data from the API and updates the cache.
     *
     * @param wgRegionId The WorldGuard region ID
     * @return CompletableFuture resolving to FetchResult<DomainRegionSummary>
     */
    public CompletableFuture<FetchResult<DomainRegionSummary>> refreshAsync(String wgRegionId) {
        Objects.requireNonNull(wgRegionId, "wgRegionId must not be null");
        
        return executor.fetchAsync(
            wgRegionId,
            settings.resolvePolicy(FetchPolicy.API_ONLY),
            () -> domainsQueryApi.getByWorldGuardRegionId(wgRegionId).thenApply(domain -> {
                if (domain != null) {
                    domainCache.put(domain);
                }
                return domain;
            })
        );
    }
    
    /**
     * Invalidate a cached domain by WorldGuard region ID.
     *
     * @param wgRegionId The WorldGuard region ID
     */
    public void invalidate(String wgRegionId) {
        Objects.requireNonNull(wgRegionId, "wgRegionId must not be null");
        domainCache.invalidate(wgRegionId);
    }
    
    /**
     * Invalidate all cached domains.
     */
    public void invalidateAll() {
        domainCache.clear();
    }

    public int cacheSize() {
        return domainCache.size();
    }

    public net.knightsandkings.knk.core.cache.DomainCache.CacheMetrics getMetrics() {
        return domainCache.getMetrics();
    }
}

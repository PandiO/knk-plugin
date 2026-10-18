package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.connectivity.ApiOutageSignal;
import net.knightsandkings.knk.core.exception.ApiDownException;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared helper that implements the unified data access policy flow.
 * <p>
 * Orchestrates cache/API interactions according to {@link FetchPolicy},
 * handles write-through caching, stale fallback, retry logic, and metrics.
 * Designed to be reused by domain-specific gateway classes to avoid
 * duplicating policy logic.
 * <p>
 * KNG-171: while the {@link ApiOutageSignal} reports DOWN, no API call is made. A read then ends
 * the way it would after a failed call (an {@link ApiDownException} in place of the connection
 * error), only at once and without retries: CACHE_FIRST and API_ONLY answer ERROR on a miss,
 * STALE_OK serves a stale entry, API_THEN_CACHE_REFRESH serves a fresh cached entry. A fresh cache
 * hit is answered as always. The signal is read before the first attempt and again before every
 * retry, so a retry already scheduled when the state turns DOWN is not sent either. Writes don't go
 * through this class and stay API-authoritative.
 *
 * @param <K> Cache key type
 * @param <V> Entity/value type
 */
public final class DataAccessExecutor<K, V> {
    
    private static final Logger LOGGER = Logger.getLogger(DataAccessExecutor.class.getName());
    
    private final BaseCache<K, V> cache;
    private final RetryPolicy retryPolicy;
    private final String entityName; // For logging/metrics context
    private final ApiOutageSignal apiOutage;
    
    /**
     * Create a DataAccessExecutor with the given cache and retry policy.
     *
     * @param cache Cache instance for this entity type
     * @param retryPolicy Retry policy for API calls
     * @param entityName Human-readable entity name (e.g., "User", "Town") for logging
     */
    public DataAccessExecutor(
        BaseCache<K, V> cache,
        RetryPolicy retryPolicy,
        String entityName
    ) {
        this(cache, retryPolicy, ApiOutageSignal.NEVER, entityName);
    }

    /**
     * Create a DataAccessExecutor with the retry policy and API outage signal of {@code settings}.
     *
     * @param cache Cache instance for this entity type
     * @param settings Gateway settings (retry policy, API outage signal)
     * @param entityName Human-readable entity name (e.g., "User", "Town") for logging
     */
    public DataAccessExecutor(
        BaseCache<K, V> cache,
        DataAccessSettings settings,
        String entityName
    ) {
        this(cache, settings.retryPolicy(), settings.apiOutage(), entityName);
    }

    /**
     * @param apiOutage while it reports DOWN, reads make no API call (KNG-171)
     */
    public DataAccessExecutor(
        BaseCache<K, V> cache,
        RetryPolicy retryPolicy,
        ApiOutageSignal apiOutage,
        String entityName
    ) {
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.apiOutage = Objects.requireNonNullElse(apiOutage, ApiOutageSignal.NEVER);
        this.entityName = Objects.requireNonNull(entityName, "entityName must not be null");
    }
    
    /**
     * Create a DataAccessExecutor with default retry policy.
     *
     * @param cache Cache instance for this entity type
     * @param entityName Human-readable entity name for logging
     */
    public DataAccessExecutor(BaseCache<K, V> cache, String entityName) {
        this(cache, RetryPolicy.defaultPolicy(), entityName);
    }
    
    // ==================== Synchronous API ====================
    
    /**
     * Execute a fetch operation synchronously according to the given policy.
     * <p>
     * <b>WARNING:</b> This method blocks. Never call from Paper main thread.
     * Use {@link #fetchAsync} for async contexts.
     *
     * @param key Cache key
     * @param policy Fetch policy to apply
     * @param apiSupplier Supplier that fetches from API (may return null for 404)
     * @return FetchResult containing the outcome
     */
    public FetchResult<V> fetchBlocking(
        K key,
        FetchPolicy policy,
        Supplier<V> apiSupplier
    ) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(apiSupplier, "apiSupplier must not be null");
        
        switch (policy) {
            case CACHE_ONLY:
                return executeCacheOnly(key);
            
            case CACHE_FIRST:
                return executeCacheFirst(key, apiSupplier);
            
            case API_ONLY:
                return executeApiOnly(key, apiSupplier);
            
            case API_THEN_CACHE_REFRESH:
                return executeApiThenCache(key, apiSupplier);
            
            case STALE_OK:
                return executeStaleOk(key, apiSupplier);
            
            default:
                throw new IllegalArgumentException("Unsupported FetchPolicy: " + policy);
        }
    }
    
    // ==================== Asynchronous API ====================
    
    /**
     * Execute a fetch operation asynchronously according to the given policy.
     * <p>
     * Non-blocking; suitable for use in listeners, commands, and scheduled tasks.
     *
     * @param key Cache key
     * @param policy Fetch policy to apply
     * @param apiSupplier Supplier that returns a CompletableFuture for API fetch
     * @return CompletableFuture of FetchResult
     */
    public CompletableFuture<FetchResult<V>> fetchAsync(
        K key,
        FetchPolicy policy,
        Supplier<CompletableFuture<V>> apiSupplier
    ) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(apiSupplier, "apiSupplier must not be null");
        
        switch (policy) {
            case CACHE_ONLY:
                return CompletableFuture.completedFuture(executeCacheOnly(key));
            
            case CACHE_FIRST:
                return executeCacheFirstAsync(key, apiSupplier);
            
            case API_ONLY:
                return executeApiOnlyAsync(key, apiSupplier);
            
            case API_THEN_CACHE_REFRESH:
                return executeApiThenCacheAsync(key, apiSupplier);
            
            case STALE_OK:
                return executeStaleOkAsync(key, apiSupplier);
            
            default:
                return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unsupported FetchPolicy: " + policy)
                );
        }
    }
    
    // ==================== Cache Management ====================
    
    /**
     * Invalidate a single cache entry.
     *
     * @param key The key to invalidate
     */
    public void invalidate(K key) {
        cache.invalidate(key);
        LOGGER.fine(() -> String.format("[%s] Invalidated cache entry: %s", entityName, key));
    }
    
    /**
     * Clear all cache entries for this entity type.
     */
    public void invalidateAll() {
        cache.clear();
        LOGGER.info(() -> String.format("[%s] Invalidated all cache entries", entityName));
    }
    
    /**
     * Manually update cache with a fresh value.
     *
     * @param key Cache key
     * @param value Value to cache
     */
    public void refresh(K key, V value) {
        cache.put(key, value);
        LOGGER.fine(() -> String.format("[%s] Manually refreshed cache: %s", entityName, key));
    }
    
    // ==================== Policy Implementations (Sync) ====================
    
    private FetchResult<V> executeCacheOnly(K key) {
        Optional<V> cached = cache.get(key);
        
        if (cached.isPresent()) {
            logCacheHit(key);
            return FetchResult.hit(cached.get());
        } else {
            logCacheMiss(key);
            return FetchResult.notFound();
        }
    }
    
    private FetchResult<V> executeCacheFirst(K key, Supplier<V> apiSupplier) {
        // 1. Try cache first
        Optional<V> cached = cache.get(key);
        if (cached.isPresent()) {
            logCacheHit(key);
            return FetchResult.hit(cached.get());
        }
        
        logCacheMiss(key);
        
        // 2. Cache miss -> fetch from API with retry
        try {
            V apiValue = callApi(apiSupplier);
            
            if (apiValue == null) {
                logApiNotFound(key);
                return FetchResult.notFound();
            }
            
            // 3. Write-through to cache
            cache.put(key, apiValue);
            logApiSuccess(key);
            return FetchResult.missFetched(apiValue);
            
        } catch (Exception e) {
            logApiError(key, e);
            return FetchResult.error(e);
        }
    }
    
    private FetchResult<V> executeApiOnly(K key, Supplier<V> apiSupplier) {
        // Bypass cache for read, but still write-through on success
        try {
            V apiValue = callApi(apiSupplier);
            
            if (apiValue == null) {
                logApiNotFound(key);
                return FetchResult.notFound();
            }
            
            // Write-through to cache for subsequent CACHE_FIRST requests
            cache.put(key, apiValue);
            logApiSuccess(key);
            return FetchResult.missFetched(apiValue);
            
        } catch (Exception e) {
            logApiError(key, e);
            return FetchResult.error(e);
        }
    }
    
    private FetchResult<V> executeApiThenCache(K key, Supplier<V> apiSupplier) {
        // Try API first; on failure fall back to cache
        try {
            V apiValue = callApi(apiSupplier);
            
            if (apiValue == null) {
                logApiNotFound(key);
                // 404 from API -> check cache as fallback
                return executeCacheOnly(key);
            }
            
            cache.put(key, apiValue);
            logApiSuccess(key);
            return FetchResult.missFetched(apiValue);
            
        } catch (Exception e) {
            logApiError(key, e);
            
            // Fallback to cache on API failure
            Optional<V> cached = cache.get(key);
            if (cached.isPresent()) {
                LOGGER.info(() -> String.format(
                    "[%s] API failed, serving cached value for: %s", entityName, key
                ));
                return FetchResult.hit(cached.get());
            }
            
            return FetchResult.error(e);
        }
    }
    
    private FetchResult<V> executeStaleOk(K key, Supplier<V> apiSupplier) {
        // Try cache first (even if stale)
        Optional<V> cached = cache.get(key);
        if (cached.isPresent()) {
            logCacheHit(key);
            return FetchResult.hit(cached.get());
        }
        
        logCacheMiss(key);
        
        // Cache miss -> try API
        try {
            V apiValue = callApi(apiSupplier);
            
            if (apiValue == null) {
                logApiNotFound(key);
                // Check for stale as last resort
                return tryStaleValue(key);
            }
            
            cache.put(key, apiValue);
            logApiSuccess(key);
            return FetchResult.missFetched(apiValue);
            
        } catch (Exception e) {
            logApiError(key, e);
            
            // API failed -> serve stale if available
            FetchResult<V> staleResult = tryStaleValue(key);
            if (staleResult.isSuccess()) {
                return staleResult;
            }
            
            return FetchResult.error(e);
        }
    }
    
    private FetchResult<V> tryStaleValue(K key) {
        Optional<V> stale = cache.getStale(key);
        if (stale.isPresent()) {
            LOGGER.warning(() -> String.format(
                "[%s] Serving stale cache value for: %s", entityName, key
            ));
            return FetchResult.staleServed(stale.get());
        }
        return FetchResult.notFound();
    }
    
    // ==================== Policy Implementations (Async) ====================
    
    private CompletableFuture<FetchResult<V>> executeCacheFirstAsync(
        K key,
        Supplier<CompletableFuture<V>> apiSupplier
    ) {
        // 1. Try cache first
        Optional<V> cached = cache.get(key);
        if (cached.isPresent()) {
            logCacheHit(key);
            return CompletableFuture.completedFuture(FetchResult.hit(cached.get()));
        }
        
        logCacheMiss(key);
        
        // 2. Cache miss -> fetch from API with retry
        return callApiAsync(apiSupplier)
            .thenApply(apiValue -> {
                if (apiValue == null) {
                    logApiNotFound(key);
                    return FetchResult.<V>notFound();
                }
                
                // 3. Write-through to cache
                cache.put(key, apiValue);
                logApiSuccess(key);
                return FetchResult.missFetched(apiValue);
            })
            .exceptionally(e -> {
                logApiError(key, e);
                return FetchResult.error(e);
            });
    }
    
    private CompletableFuture<FetchResult<V>> executeApiOnlyAsync(
        K key,
        Supplier<CompletableFuture<V>> apiSupplier
    ) {
        return callApiAsync(apiSupplier)
            .thenApply(apiValue -> {
                if (apiValue == null) {
                    logApiNotFound(key);
                    return FetchResult.<V>notFound();
                }
                
                cache.put(key, apiValue);
                logApiSuccess(key);
                return FetchResult.missFetched(apiValue);
            })
            .exceptionally(e -> {
                logApiError(key, e);
                return FetchResult.error(e);
            });
    }
    
    private CompletableFuture<FetchResult<V>> executeApiThenCacheAsync(
        K key,
        Supplier<CompletableFuture<V>> apiSupplier
    ) {
        return callApiAsync(apiSupplier)
            .thenApply(apiValue -> {
                if (apiValue == null) {
                    logApiNotFound(key);
                    // 404 from API -> try cache
                    return executeCacheOnly(key);
                }
                
                cache.put(key, apiValue);
                logApiSuccess(key);
                return FetchResult.missFetched(apiValue);
            })
            .exceptionally(e -> {
                logApiError(key, e);
                
                // Fallback to cache on API failure
                Optional<V> cached = cache.get(key);
                if (cached.isPresent()) {
                    LOGGER.info(() -> String.format(
                        "[%s] API failed (async), serving cached value for: %s", entityName, key
                    ));
                    return FetchResult.hit(cached.get());
                }
                
                return FetchResult.error(e);
            });
    }
    
    private CompletableFuture<FetchResult<V>> executeStaleOkAsync(
        K key,
        Supplier<CompletableFuture<V>> apiSupplier
    ) {
        // Try cache first (even if stale)
        Optional<V> cached = cache.get(key);
        if (cached.isPresent()) {
            logCacheHit(key);
            return CompletableFuture.completedFuture(FetchResult.hit(cached.get()));
        }
        
        logCacheMiss(key);
        
        // Cache miss -> try API
        return callApiAsync(apiSupplier)
            .thenApply(apiValue -> {
                if (apiValue == null) {
                    logApiNotFound(key);
                    return tryStaleValue(key);
                }
                
                cache.put(key, apiValue);
                logApiSuccess(key);
                return FetchResult.missFetched(apiValue);
            })
            .exceptionally(e -> {
                logApiError(key, e);
                
                // API failed -> serve stale if available
                FetchResult<V> staleResult = tryStaleValue(key);
                if (staleResult.isSuccess()) {
                    return staleResult;
                }
                
                return FetchResult.error(e);
            });
    }
    
    // ==================== API calls (KNG-171 outage gate) ====================

    /** The API call with retries, unless the API is DOWN: then it throws without calling. */
    private V callApi(Supplier<V> apiSupplier) throws Exception {
        if (apiOutage.isApiDown()) {
            throw apiDown();
        }
        return retryPolicy.execute(() -> {
            if (apiOutage.isApiDown()) {
                throw apiDown();
            }
            return apiSupplier.get();
        });
    }

    /** The API call with retries, unless the API is DOWN: then it fails at once without calling. */
    private CompletableFuture<V> callApiAsync(Supplier<CompletableFuture<V>> apiSupplier) {
        if (apiOutage.isApiDown()) {
            return CompletableFuture.failedFuture(apiDown());
        }
        return retryPolicy.executeAsync(() -> apiOutage.isApiDown()
            ? CompletableFuture.failedFuture(apiDown())
            : apiSupplier.get());
    }

    private ApiDownException apiDown() {
        return new ApiDownException(entityName);
    }

    // ==================== Logging Helpers ====================
    
    private void logCacheHit(K key) {
        LOGGER.fine(() -> String.format("[%s] Cache HIT: %s", entityName, key));
    }
    
    private void logCacheMiss(K key) {
        LOGGER.fine(() -> String.format("[%s] Cache MISS: %s", entityName, key));
    }
    
    private void logApiSuccess(K key) {
        LOGGER.info(() -> String.format("[%s] API fetch successful: %s", entityName, key));
    }
    
    private void logApiNotFound(K key) {
        LOGGER.info(() -> String.format("[%s] API returned 404 (not found): %s", entityName, key));
    }
    
    private void logApiError(K key, Throwable error) {
        if (ApiDownException.isApiDown(error)) {
            // Expected on every miss during an outage; the connectivity monitor already logged DOWN.
            LOGGER.fine(() -> String.format("[%s] API is DOWN, not requested: %s", entityName, key));
            return;
        }
        LOGGER.log(
            Level.WARNING,
            String.format("[%s] API fetch failed for: %s", entityName, key),
            error
        );
    }
}

package net.knightsandkings.knk.core.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generic, TTL-aware cache for domain entities retrieved from the Web API.
 * <p>
 * Design principles:
 * <ul>
 *   <li><b>Time-bounded (TTL)</b>: Stale data is acceptable; consistency is eventual.</li>
 *   <li><b>Non-blocking</b>: Never performs I/O directly (READ-ONLY cache).</li>
 *   <li><b>Observable</b>: Tracks hits, misses, stale hits, evictions for monitoring.</li>
 *   <li><b>Thread-safe</b>: Uses ConcurrentHashMap with atomic operations.</li>
 * </ul>
 * <p>
 * This cache is suitable for caching API responses that are expensive to fetch but can
 * tolerate some staleness. The cache does not automatically refresh entries; callers
 * must handle refetching when needed.
 *
 * @param <K> The key type (e.g., Integer for IDs, String for region IDs)
 * @param <V> The value type (domain entity or DTO)
 */
public class DomainCache<K, V> {

    private final Duration defaultTtl;
    private final Map<K, CachedEntry<V>> entries = new ConcurrentHashMap<>();
    private final CacheMetrics metrics = new CacheMetrics();

    /**
     * Creates a new domain cache with the specified TTL.
     *
     * @param defaultTtl Time-to-live for cached entries. If null, defaults to 1 minute.
     */
    public DomainCache(Duration defaultTtl) {
        this.defaultTtl = defaultTtl != null ? defaultTtl : Duration.ofMinutes(1);
    }

    /**
     * Retrieves a value from the cache.
     * <p>
     * Returns empty if:
     * <ul>
     *   <li>The key has never been cached</li>
     *   <li>The cached entry has expired (based on TTL)</li>
     * </ul>
     * <p>
     * This method does NOT trigger background refresh. The caller is responsible
     * for fetching from the API if a cache miss occurs.
     *
     * @param key The cache key
     * @return Optional containing the cached value if present and not expired, empty otherwise
     */
    public Optional<V> get(K key) {
        if (key == null) {
            return Optional.empty();
        }

        CachedEntry<V> entry = entries.get(key);
        if (entry == null) {
            metrics.recordMiss();
            return Optional.empty();
        }

        if (entry.isExpired(defaultTtl)) {
            metrics.recordStaleHit();
            // Treat expired entries as misses (caller should refetch)
            return Optional.empty();
        }

        metrics.recordHit();
        return Optional.of(entry.value);
    }

    /**
     * Retrieves a value from the cache even if expired (stale-while-revalidate pattern).
     * <p>
     * This is useful when you want to serve stale data while asynchronously refetching
     * the latest version in the background. Returns empty only if the key has never
     * been cached.
     *
     * @param key The cache key
     * @return Optional containing the cached value (even if stale), empty if never cached
     */
    public Optional<V> getStale(K key) {
        if (key == null) {
            return Optional.empty();
        }

        CachedEntry<V> entry = entries.get(key);
        if (entry != null) {
            metrics.recordStaleHit();
            return Optional.of(entry.value);
        }

        metrics.recordMiss();
        return Optional.empty();
    }

    /**
     * Stores a value in the cache with the current timestamp.
     * <p>
     * Null values are ignored (not cached).
     *
     * @param key   The cache key
     * @param value The value to cache (must not be null)
     */
    public void put(K key, V value) {
        if (key == null || value == null) {
            return;
        }

        entries.put(key, new CachedEntry<>(value, Instant.now()));
        metrics.recordPut();
    }

    /**
     * Batch stores multiple values from an API response.
     * <p>
     * This is more efficient than calling {@link #put(Object, Object)} repeatedly,
     * especially when populating the cache from a search/list API endpoint.
     * <p>
     * Null keys or values are skipped.
     *
     * @param values Map of key-value pairs to cache
     */
    public void putAll(Map<K, V> values) {
        if (values == null || values.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        int cached = 0;

        for (Map.Entry<K, V> entry : values.entrySet()) {
            K key = entry.getKey();
            V value = entry.getValue();

            if (key != null && value != null) {
                entries.put(key, new CachedEntry<>(value, now));
                cached++;
            }
        }

        metrics.recordBatchPut(cached);
    }

    /**
     * Removes a specific entry from the cache.
     * <p>
     * This is useful when you know data has changed on the server (e.g., after
     * a write operation) and want to force a refetch on the next access.
     *
     * @param key The cache key to invalidate
     */
    public void invalidate(K key) {
        if (key != null && entries.remove(key) != null) {
            metrics.recordEviction();
        }
    }

    /**
     * Clears all entries from the cache.
     * <p>
     * Typically used during plugin reload or configuration changes.
     */
    public void clear() {
        int size = entries.size();
        entries.clear();
        metrics.recordClear(size);
    }

    /**
     * Returns the current number of entries in the cache (including expired ones).
     *
     * @return Number of cached entries
     */
    public int size() {
        return entries.size();
    }

    /** Thread-safe snapshot of all cached values, including stale entries. */
    public List<V> valuesSnapshot() {
        return entries.values().stream().map(entry -> entry.value).toList();
    }

    /**
     * Returns the cache metrics for monitoring and debugging.
     *
     * @return CacheMetrics instance with hit/miss/eviction counts
     */
    public CacheMetrics getMetrics() {
        return metrics;
    }

    // ============================================================================
    // Inner Classes
    // ============================================================================

    /**
     * Represents a cached entry with its value and timestamp.
     *
     * @param <V> The value type
     */
    private static class CachedEntry<V> {
        final V value;
        final Instant cachedAt;

        CachedEntry(V value, Instant cachedAt) {
            this.value = value;
            this.cachedAt = cachedAt;
        }

        /**
         * Checks if this entry has expired based on the given TTL.
         *
         * @param ttl Time-to-live duration
         * @return true if the entry is older than the TTL
         */
        boolean isExpired(Duration ttl) {
            return cachedAt.plus(ttl).isBefore(Instant.now());
        }
    }

    /**
     * Cache metrics for observability and performance monitoring.
     * <p>
     * All operations are thread-safe using atomic counters.
     */
    public static class CacheMetrics {
        private final AtomicLong hits = new AtomicLong(0);
        private final AtomicLong misses = new AtomicLong(0);
        private final AtomicLong staleHits = new AtomicLong(0);
        private final AtomicLong puts = new AtomicLong(0);
        private final AtomicLong evictions = new AtomicLong(0);

    public void recordHit() {
            hits.incrementAndGet();
        }

    public void recordMiss() {
            misses.incrementAndGet();
        }

    public void recordStaleHit() {
            staleHits.incrementAndGet();
        }

    public void recordPut() {
            puts.incrementAndGet();
        }

    public void recordBatchPut(int count) {
            puts.addAndGet(count);
        }

    public void recordEviction() {
            evictions.incrementAndGet();
        }

    public void recordClear(int count) {
            evictions.addAndGet(count);
        }

        public long getHits() {
            return hits.get();
        }

        public long getMisses() {
            return misses.get();
        }

        public long getStaleHits() {
            return staleHits.get();
        }

        public long getPuts() {
            return puts.get();
        }

        public long getEvictions() {
            return evictions.get();
        }

        /**
         * Calculates the cache hit rate as a percentage (0-100).
         *
         * @return Hit rate percentage, or 0 if no requests yet
         */
        public long getHitRate() {
            long total = hits.get() + misses.get();
            return total == 0 ? 0 : (100 * hits.get()) / total;
        }

        @Override
        public String toString() {
            return String.format(
                "hits=%d, misses=%d, stale=%d, puts=%d, evictions=%d, hitRate=%d%%",
                hits.get(), misses.get(), staleHits.get(), puts.get(), evictions.get(), getHitRate()
            );
        }

        /**
         * Resets all metrics to zero.
         * Useful for testing or periodic metric snapshots.
         */
        public void reset() {
            hits.set(0);
            misses.set(0);
            staleHits.set(0);
            puts.set(0);
            evictions.set(0);
        }
    }
}

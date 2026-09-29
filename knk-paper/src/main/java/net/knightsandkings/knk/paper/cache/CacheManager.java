package net.knightsandkings.knk.paper.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.knightsandkings.knk.core.cache.*;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.dataaccess.DataAccessFactory;

/**
 * Centralized cache lifecycle manager for the Knights & Kings plugin.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Initializes all domain caches with configured TTLs</li>
 *   <li>Provides single access point for cache instances</li>
 *   <li>Logs cache metrics for monitoring and debugging</li>
 *   <li>Handles cache clearing on plugin reload</li>
 * </ul>
 * <p>
 * This class is the bridge between the plugin configuration (Paper layer)
 * and the pure cache logic (core layer).
 */
public class CacheManager {

    private static final Logger LOGGER = Logger.getLogger(CacheManager.class.getName());

    private final TownCache townCache;
    private final DistrictCache districtCache;
    private final StructureCache structureCache;
    private final UserCache userCache;

    private final Duration cacheTtl;
    private RegionDomainResolver regionResolver; // Optional - set after initialization
    /** Caches kept outside this manager that {@code /knk cache refresh} drops, by display name. */
    private final Map<String, Runnable> refreshHooks = new ConcurrentHashMap<>();

    /**
     * Creates a new cache manager with the specified TTL for all caches.
     *
     * @param cacheTtl Time-to-live for cached entries. If null, defaults to 1 minute.
     */
    public CacheManager(Duration cacheTtl) {
        this.cacheTtl = cacheTtl != null ? cacheTtl : Duration.ofMinutes(1);

        LOGGER.info("Initializing cache manager with TTL: " + this.cacheTtl);

        this.townCache = new TownCache(this.cacheTtl);
        this.districtCache = new DistrictCache(this.cacheTtl);
        this.structureCache = new StructureCache(this.cacheTtl);
        this.userCache = new UserCache(this.cacheTtl);

        LOGGER.info("Cache manager initialized successfully");
    }

    /**
     * Returns the town cache instance.
     *
     * @return TownCache
     */
    public TownCache getTownCache() {
        return townCache;
    }

    /**
     * Returns the district cache instance.
     *
     * @return DistrictCache
     */
    public DistrictCache getDistrictCache() {
        return districtCache;
    }

    /**
     * Returns the structure cache instance.
     *
     * @return StructureCache
     */
    public StructureCache getStructureCache() {
        return structureCache;
    }

    /**
     * Returns the user cache instance.
     *
     * @return UserCache
     */
    public UserCache getUserCache() {
        return userCache;
    }

    /**
     * Set the region resolver for tracking its cache metrics.
     * Called after bootstrap wiring is complete.
     */
    public void setRegionResolver(RegionDomainResolver resolver) {
        this.regionResolver = resolver;
    }

    /**
     * Logs cache metrics for all domain caches.
     * <p>
     * This is useful for debugging performance issues or monitoring cache effectiveness.
     * Can be called manually via command or periodically via a scheduled task.
     */
    public void logMetrics() {
        LOGGER.info("========== Cache Metrics ==========");
        LOGGER.info(String.format("Towns     : %s (size=%d)",
            townCache.getMetrics(), townCache.size()));
        LOGGER.info(String.format("Districts : %s (size=%d)",
            districtCache.getMetrics(), districtCache.size()));
        LOGGER.info(String.format("Structures: %s (size=%d)",
            structureCache.getMetrics(), structureCache.size()));
        LOGGER.info(String.format("Users     : %s (size=%d)",
            userCache.getMetrics(), userCache.size()));
                if (regionResolver != null) {
                    LOGGER.info(String.format("Domains   : %s (size=%d)",
                        regionResolver.getDomainCacheMetrics(), regionResolver.getDomainCacheSize()));
                }
        LOGGER.info("===================================");
    }

    /**
     * Clears all caches.
     * <p>
     * Typically used:
     * <ul>
     *   <li>During plugin reload</li>
     *   <li>When configuration changes</li>
     *   <li>When forcing a fresh data fetch from the API</li>
     * </ul>
     */
    public void clearAll() {
        LOGGER.info("Clearing all caches...");

        townCache.clear();
        districtCache.clear();
        structureCache.clear();
        userCache.clear();

        LOGGER.info("All caches cleared");
    }

    /**
     * Register a cache kept elsewhere (e.g. the {@code /spawn} destination) so {@code /knk cache refresh}
     * drops it too.
     *
     * @param name    shown to the staff member who refreshes
     * @param refresh drops the cached data; must be cheap and thread-safe
     */
    public void registerRefreshHook(String name, Runnable refresh) {
        refreshHooks.put(name, refresh);
    }

    /**
     * {@code /knk cache refresh}: runs every registered refresh hook.
     *
     * @return the names of the caches that were dropped, sorted
     */
    public List<String> runRefreshHooks() {
        refreshHooks.forEach((name, refresh) -> {
            try {
                refresh.run();
            } catch (RuntimeException ex) {
                LOGGER.log(Level.WARNING, "Refreshing the " + name + " cache failed", ex);
            }
        });
        return refreshHooks.keySet().stream().sorted().toList();
    }

    /**
     * Resets all cache metrics (hit/miss counters).
     * <p>
     * Useful for periodic metric snapshots or when you want to measure
     * cache performance over a specific time window.
     */
    public void resetMetrics() {
        LOGGER.info("Resetting cache metrics...");

        townCache.getMetrics().reset();
        districtCache.getMetrics().reset();
        structureCache.getMetrics().reset();
        userCache.getMetrics().reset();

        LOGGER.info("Cache metrics reset");
    }

    /**
     * Returns a summary of cache health for all caches.
     * <p>
     * This provides a quick overview suitable for status commands or dashboards.
     *
     * @return Multi-line string with cache statistics
     */
    public String getHealthSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("§6Cache Health:§r\n");
        sb.append(String.format("  §eTowns§r: %d entries, %d%% hit rate\n",
            townCache.size(), townCache.getMetrics().getHitRate()));
        sb.append(String.format("  §eDistricts§r: %d entries, %d%% hit rate\n",
            districtCache.size(), districtCache.getMetrics().getHitRate()));
        sb.append(String.format("  §eStructures§r: %d entries, %d%% hit rate\n",
            structureCache.size(), structureCache.getMetrics().getHitRate()));
        sb.append(String.format("  §eUsers§r: %d entries, %d%% hit rate\n",
            userCache.size(), userCache.getMetrics().getHitRate()));
                if (regionResolver != null) {
                    sb.append(String.format("  §eDomains§r: %d entries, %d%% hit rate\n",
                        regionResolver.getDomainCacheSize(), regionResolver.getDomainCacheMetrics().getHitRate()));
                }
        sb.append(String.format("  §eTTL§r: %s", formatDuration(cacheTtl)));
        return sb.toString();
    }

    /**
     * Formats a duration in a human-readable way.
     *
     * @param duration Duration to format
     * @return Human-readable string (e.g., "1m 30s")
     */
    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;

        if (minutes > 0) {
            return String.format("%dm %ds", minutes, remainingSeconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}

/**
 * Unified data access layer for knk-plugin.
 * <p>
 * Provides a consistent, cache-aware data access strategy that:
 * <ul>
 *   <li>Minimizes repetitive cache/API orchestration code</li>
 *   <li>Prefers cache when possible, falls back to Web API</li>
 *   <li>Supports multiple fetch policies (CACHE_ONLY, CACHE_FIRST, API_ONLY, etc.)</li>
 *   <li>Handles write-through caching, stale fallback, and retry logic</li>
 *   <li>Provides typed, consistent error handling via {@link net.knightsandkings.knk.core.dataaccess.FetchResult}</li>
 * </ul>
 *
 * <h2>Core Components</h2>
 * <ul>
 *   <li>{@link net.knightsandkings.knk.core.dataaccess.FetchPolicy} - Defines retrieval strategy</li>
 *   <li>{@link net.knightsandkings.knk.core.dataaccess.FetchResult} - Type-safe result wrapper</li>
 *   <li>{@link net.knightsandkings.knk.core.dataaccess.DataAccessExecutor} - Shared policy executor</li>
 *   <li>{@link net.knightsandkings.knk.core.dataaccess.RetryPolicy} - Configurable retry with exponential backoff</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create executor with cache and retry policy
 * DataAccessExecutor<UUID, UserSummary> executor = new DataAccessExecutor<>(
 *     userCache,
 *     RetryPolicy.defaultPolicy(),
 *     "User"
 * );
 *
 * // Fetch with cache-first policy (async)
 * CompletableFuture<FetchResult<UserSummary>> future = executor.fetchAsync(
 *     playerUuid,
 *     FetchPolicy.CACHE_FIRST,
 *     () -> usersQueryApi.getByUuid(playerUuid)
 * );
 *
 * future.thenAccept(result -> {
 *     result.ifSuccess(user -> {
 *         // Handle successful fetch (from cache or API)
 *         plugin.getLogger().info("User loaded: " + user.username());
 *     }).ifError(error -> {
 *         // Handle error
 *         plugin.getLogger().warning("Failed to load user: " + error.getMessage());
 *     });
 * });
 * }</pre>
 *
 * <h2>Fetch Policies</h2>
 * <ul>
 *   <li><b>CACHE_ONLY</b> - Only check cache; never hit API (use for offline mode)</li>
 *   <li><b>CACHE_FIRST</b> - Check cache first; on miss fetch from API (default, optimal performance)</li>
 *   <li><b>API_ONLY</b> - Always fetch from API; still write-through to cache (use for guaranteed fresh data)</li>
 *   <li><b>API_THEN_CACHE_REFRESH</b> - Fetch API first; fall back to cache on failure</li>
 *   <li><b>STALE_OK</b> - Serve stale cache data if API fails (most resilient, use for non-critical reads)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * All components are thread-safe. The async API ({@code fetchAsync}) is non-blocking
 * and suitable for Paper main thread use. The sync API ({@code fetchBlocking}) should
 * only be called from async threads (e.g., async pre-login event).
 *
 * @see net.knightsandkings.knk.core.cache.BaseCache
 * @see net.knightsandkings.knk.core.ports.api
 * @since Phase 2 - Foundations
 */
package net.knightsandkings.knk.core.dataaccess;

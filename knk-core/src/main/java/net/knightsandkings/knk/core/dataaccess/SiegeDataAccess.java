package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeReadiness;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeRuntimeConfig;
import net.knightsandkings.knk.core.ports.api.SiegeLobbiesQueryApi;
import net.knightsandkings.knk.core.ports.api.SiegeScenariosQueryApi;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Siege gateway (Siege Phase 4, DESIGN §5.1). The runtime-config payload is one cache entry, read
 * cache-first.
 * <p>
 * <b>Refresh rule:</b> a lobby's configuration is frozen for a whole match. The Paper runtime calls
 * {@link #refreshRuntimeConfigAsync()} only between matches (the lobby state machine's
 * {@code RefreshConfigEffect} on entering cooldown, {@code /siege admin reload}) and hands the result
 * to lobbies that aren't running a match. Nothing in here refreshes on its own schedule; the TTL
 * only bounds how old a cache-first read may be.
 * <p>
 * Readiness is an admin diagnostic and is never cached.
 */
public class SiegeDataAccess {

    public static final String RUNTIME_CONFIG_KEY = "runtime-config";

    private static final Logger LOGGER = Logger.getLogger(SiegeDataAccess.class.getName());

    private final RuntimeConfigCache cache;
    private final SiegeLobbiesQueryApi lobbiesApi;
    private final SiegeScenariosQueryApi scenariosApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<String, KnkSiegeRuntimeConfig> executor;

    private static class RuntimeConfigCache extends BaseCache<String, KnkSiegeRuntimeConfig> {
        RuntimeConfigCache(Duration ttl) {
            super(ttl);
        }
    }

    public SiegeDataAccess(Duration ttl, SiegeLobbiesQueryApi lobbiesApi, SiegeScenariosQueryApi scenariosApi) {
        this(ttl, lobbiesApi, scenariosApi, DataAccessSettings.defaults());
    }

    public SiegeDataAccess(
            Duration ttl,
            SiegeLobbiesQueryApi lobbiesApi,
            SiegeScenariosQueryApi scenariosApi,
            DataAccessSettings settings
    ) {
        this.cache = new RuntimeConfigCache(ttl);
        this.lobbiesApi = Objects.requireNonNull(lobbiesApi, "lobbiesApi must not be null");
        this.scenariosApi = Objects.requireNonNull(scenariosApi, "scenariosApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(cache, this.settings.retryPolicy(), "SiegeRuntimeConfig");
    }

    /** The runtime config with the configured default policy (CACHE_FIRST unless config.yml says otherwise). */
    public CompletableFuture<FetchResult<KnkSiegeRuntimeConfig>> getRuntimeConfigAsync() {
        return getRuntimeConfigAsync(null);
    }

    public CompletableFuture<FetchResult<KnkSiegeRuntimeConfig>> getRuntimeConfigAsync(FetchPolicy policy) {
        return executor.fetchAsync(RUNTIME_CONFIG_KEY, settings.resolvePolicy(policy), lobbiesApi::getRuntimeConfig);
    }

    /**
     * Fetch a fresh runtime config from the API (with retry) and cache it. Call only between matches.
     * If the API still fails, the last cached copy is served (marked stale when it has expired, and
     * only if stale reads are allowed), so a lobby keeps running on its previous configuration.
     */
    public CompletableFuture<FetchResult<KnkSiegeRuntimeConfig>> refreshRuntimeConfigAsync() {
        return executor.fetchAsync(RUNTIME_CONFIG_KEY, FetchPolicy.API_THEN_CACHE_REFRESH, lobbiesApi::getRuntimeConfig)
                .thenApply(result -> {
                    if (result.isSuccess() || !settings.allowStale()) return result;
                    Optional<KnkSiegeRuntimeConfig> stale = cache.getStale(RUNTIME_CONFIG_KEY);
                    if (stale.isEmpty()) return result;
                    LOGGER.warning("[SiegeRuntimeConfig] Refresh failed, keeping the previous (expired) configuration");
                    return FetchResult.staleServed(stale.get());
                });
    }

    /** The last fetched runtime config, even if expired; no API call. */
    public Optional<KnkSiegeRuntimeConfig> cachedRuntimeConfig() {
        return cache.getStale(RUNTIME_CONFIG_KEY);
    }

    /** A scenario's readiness straight from the API (never cached); null when the scenario doesn't exist. */
    public CompletableFuture<KnkSiegeReadiness> getReadinessAsync(int scenarioId) {
        return scenariosApi.getReadiness(scenarioId);
    }

    public void invalidateAll() {
        executor.invalidateAll();
    }
}

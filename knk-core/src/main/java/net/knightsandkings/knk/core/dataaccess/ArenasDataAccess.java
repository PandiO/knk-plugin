package net.knightsandkings.knk.core.dataaccess;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaArea;
import net.knightsandkings.knk.core.ports.api.ArenasApi;

/**
 * Arena gateway (arena duels KNG-210, DESIGN.md §4.2: "the plugin reads the settings through a cache-first gateway and
 * refreshes on change"). Every arena is one cache entry, read cache-first.
 * <p>
 * <b>Refresh rule:</b> the runtime calls {@link #refreshAsync()} when the settings may have changed (an admin reload,
 * before a new negotiation reads its arena); a match keeps the arena it locked in with. A failed refresh serves the
 * last copy (stale when expired and stale reads are allowed), so duels keep running on the previous settings while the
 * API is down. The lookups ({@link #cachedByKey}, {@link #cachedAreaByRegion}, {@link #cachedArenaByRegion}) never call the
 * API.
 */
public class ArenasDataAccess {

    public static final String ALL_KEY = "all";

    private static final Logger LOGGER = Logger.getLogger(ArenasDataAccess.class.getName());

    private final ArenaListCache cache;
    private final ArenasApi arenasApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<String, List<KnkArena>> executor;

    private static class ArenaListCache extends BaseCache<String, List<KnkArena>> {
        ArenaListCache(Duration ttl) {
            super(ttl);
        }
    }

    public ArenasDataAccess(Duration ttl, ArenasApi arenasApi) {
        this(ttl, arenasApi, DataAccessSettings.defaults());
    }

    public ArenasDataAccess(Duration ttl, ArenasApi arenasApi, DataAccessSettings settings) {
        this.cache = new ArenaListCache(ttl);
        this.arenasApi = Objects.requireNonNull(arenasApi, "arenasApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(cache, this.settings, "Arenas");
    }

    /** Every arena with the configured default policy (CACHE_FIRST unless config.yml says otherwise). */
    public CompletableFuture<FetchResult<List<KnkArena>>> getAllAsync() {
        return getAllAsync(null);
    }

    public CompletableFuture<FetchResult<List<KnkArena>>> getAllAsync(FetchPolicy policy) {
        return executor.fetchAsync(ALL_KEY, settings.resolvePolicy(policy), arenasApi::getAll);
    }

    /** One arena by its key (any case), through {@link #getAllAsync()}. */
    public CompletableFuture<Optional<KnkArena>> getByKeyAsync(String key) {
        return getAllAsync().thenApply(result -> result.value().flatMap(arenas -> byKey(arenas, key)));
    }

    /**
     * Fetch every arena fresh from the API (with retry) and cache the list. If the API still fails, the last cached
     * copy is served (marked stale when it has expired, and only if stale reads are allowed).
     */
    public CompletableFuture<FetchResult<List<KnkArena>>> refreshAsync() {
        return executor.fetchAsync(ALL_KEY, FetchPolicy.API_THEN_CACHE_REFRESH, arenasApi::getAll)
                .thenApply(result -> {
                    if (result.isSuccess() || !settings.allowStale()) return result;
                    Optional<List<KnkArena>> stale = cache.getStale(ALL_KEY);
                    if (stale.isEmpty()) return result;
                    LOGGER.warning("[Arenas] Refresh failed, keeping the previous (expired) arena settings");
                    return FetchResult.staleServed(stale.get());
                });
    }

    /** The last fetched arenas, even if expired; no API call. Empty before the first successful read. */
    public Optional<List<KnkArena>> cachedArenas() {
        return cache.getStale(ALL_KEY);
    }

    /** A cached arena by key (any case); no API call. */
    public Optional<KnkArena> cachedByKey(String key) {
        return cachedArenas().flatMap(arenas -> byKey(arenas, key));
    }

    /** A cached arena by id; no API call. */
    public Optional<KnkArena> cachedById(int arenaId) {
        return cachedArenas().flatMap(arenas -> arenas.stream().filter(a -> a.id() == arenaId).findFirst());
    }

    /**
     * The cached arena area (any kind, enabled or not; arena duels rev. 6) one of whose regions is {@code regionId} (any
     * case) in {@code worldName} (null or blank matches any world; region ids are only unique per world); no API call.
     */
    public Optional<KnkArenaArea> cachedAreaByRegion(String worldName, String regionId) {
        return cachedArenas().flatMap(arenas -> areaByRegion(arenas, worldName, regionId));
    }

    /**
     * The cached arena {@code regionId} belongs to: its main region, one of its KNG-253 sub-regions, or one of its
     * areas' regions; no API call.
     */
    public Optional<KnkArena> cachedArenaByRegion(String worldName, String regionId) {
        return cachedArenas().flatMap(arenas -> arenaByRegion(arenas, worldName, regionId));
    }

    /** {@link #cachedAreaByRegion} over a given list. */
    public static Optional<KnkArenaArea> areaByRegion(List<KnkArena> arenas, String worldName, String regionId) {
        if (arenas == null || regionId == null || regionId.isBlank()) {
            return Optional.empty();
        }
        return arenas.stream()
                .flatMap(arena -> arena.areas().stream())
                .filter(area -> area.hasRegion(worldName, regionId))
                .findFirst();
    }

    /** {@link #cachedArenaByRegion} over a given list. */
    public static Optional<KnkArena> arenaByRegion(List<KnkArena> arenas, String worldName, String regionId) {
        if (arenas == null || regionId == null || regionId.isBlank()) {
            return Optional.empty();
        }
        for (KnkArena arena : arenas) {
            boolean sameWorld = worldName == null || worldName.isBlank() || arena.worldName() == null
                    || arena.worldName().isBlank() || arena.worldName().equalsIgnoreCase(worldName);
            if (sameWorld && arena.isOwnRegion(regionId)) {
                return Optional.of(arena);
            }
            if (arena.areas().stream().anyMatch(area -> area.hasRegion(worldName, regionId))) {
                return Optional.of(arena);
            }
        }
        return Optional.empty();
    }

    public void invalidateAll() {
        executor.invalidateAll();
    }

    private static Optional<KnkArena> byKey(List<KnkArena> arenas, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        return arenas.stream().filter(a -> a.key() != null && a.key().toLowerCase(Locale.ROOT).equals(wanted)).findFirst();
    }
}

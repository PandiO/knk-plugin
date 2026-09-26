package net.knightsandkings.knk.core.dataaccess;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.ports.api.TeleportDestinationsQueryApi;

/**
 * A per-player cache of the {@code /warp} destination list (docs/specs/teleport/DESIGN.md §3.7.4):
 * each player's list is fetched once and kept for {@code teleport.destinations.cache-seconds}
 * ({@link CachedList}: completed future while fresh, one shared in-flight request, the stale list
 * when a refresh fails). Only for showing and resolving names - the charge call re-checks access
 * server-side, so a stale list never lets anyone through. Dropped after a charge (their gems
 * changed), when they leave, and on {@code /knk cache refresh}.
 */
public class TeleportDestinationsDataAccess {

    private final TeleportDestinationsQueryApi queryApi;
    private final Clock clock;
    private final Map<Integer, CachedList<KnkTeleportDestination>> lists = new ConcurrentHashMap<>();
    private final Duration ttl;

    public TeleportDestinationsDataAccess(TeleportDestinationsQueryApi queryApi, Duration ttl) {
        this(queryApi, ttl, Clock.systemUTC());
    }

    public TeleportDestinationsDataAccess(TeleportDestinationsQueryApi queryApi, Duration ttl, Clock clock) {
        this.queryApi = queryApi;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** The player's destinations, Towns first (the server's order). */
    public CompletableFuture<List<KnkTeleportDestination>> listAsync(int userId) {
        return lists.computeIfAbsent(userId, id -> new CachedList<>(() -> queryApi.listForUser(id), ttl, clock)).getAsync();
    }

    /** The player's cached list (possibly stale), else empty. Never does I/O - for tab completion. */
    public List<KnkTeleportDestination> cachedOrEmpty(int userId) {
        CachedList<KnkTeleportDestination> list = lists.get(userId);
        return list != null ? list.cachedOrEmpty() : List.of();
    }

    /** Fetch again next time (after a charge: the lock state depends on the balance). */
    public void invalidate(int userId) {
        CachedList<KnkTeleportDestination> list = lists.get(userId);
        if (list != null) {
            list.invalidate();
        }
    }

    /** Forget a player entirely (they left). */
    public void forget(int userId) {
        lists.remove(userId);
    }

    /** Drop every cached list ({@code /knk cache refresh}, a changed cache time). */
    public void invalidateAll() {
        lists.clear();
    }
}

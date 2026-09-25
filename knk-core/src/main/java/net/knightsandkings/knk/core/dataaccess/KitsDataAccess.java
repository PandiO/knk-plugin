package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.item.KnkKit;
import net.knightsandkings.knk.core.domain.item.KnkKitAvailability;
import net.knightsandkings.knk.core.ports.api.KitsQueryApi;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Cache-first read gateway for the Kit catalog, same {@link FetchPolicy}/{@link FetchResult}/
 * {@link DataAccessExecutor} shape as {@link ItemBlueprintsDataAccess} (docs/specs/kits/
 * IMPLEMENTATION_PLAN.md §4).
 * <p>
 * {@link #getAvailableForUserAsync(int)} is deliberately never cached: it reflects per-user
 * cooldown/cost/purchase state that must be fresh every time {@code /kit list} is run, the same
 * reasoning DESIGN.md §4.1 gives for why {@code ClaimKitAsync} never trusts a caller's own prior
 * {@code GetAvailableForUserAsync} result either - always a direct passthrough to the API.
 */
public class KitsDataAccess {

    private final KitCache cache;
    private final KitsQueryApi queryApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<Integer, KnkKit> executor;

    private static class KitCache extends BaseCache<Integer, KnkKit> {
        public KitCache(Duration ttl) {
            super(ttl);
        }

        public void put(KnkKit kit) {
            if (kit != null && kit.id() != null) {
                put(kit.id(), kit);
            }
        }
    }

    public KitsDataAccess(Duration ttl, KitsQueryApi queryApi) {
        this(ttl, queryApi, DataAccessSettings.defaults());
    }

    public KitsDataAccess(Duration ttl, KitsQueryApi queryApi, DataAccessSettings settings) {
        this.cache = new KitCache(ttl);
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(cache, this.settings.retryPolicy(), "Kit");
    }

    public CompletableFuture<FetchResult<KnkKit>> getByIdAsync(int id, FetchPolicy policy) {
        policy = settings.resolvePolicy(policy);

        return executor.fetchAsync(
                id,
                policy,
                () -> queryApi.getById(id).thenApply(kit -> {
                    if (kit != null) {
                        cache.put(kit);
                    }
                    return kit;
                })
        );
    }

    public CompletableFuture<FetchResult<KnkKit>> getByIdAsync(int id) {
        return getByIdAsync(id, null);
    }

    public CompletableFuture<FetchResult<KnkKit>> refreshAsync(int id) {
        return executor.fetchAsync(
                id,
                settings.resolvePolicy(FetchPolicy.API_ONLY),
                () -> queryApi.getById(id).thenApply(kit -> {
                    if (kit != null) {
                        cache.put(kit);
                    }
                    return kit;
                })
        );
    }

    public CompletableFuture<Page<KnkKit>> searchAsync(PagedQuery query) {
        return queryApi.search(query).thenApply(page -> {
            if (page != null && page.items() != null) {
                for (KnkKit kit : page.items()) {
                    cache.put(kit);
                }
            }
            return page;
        });
    }

    public CompletableFuture<Page<KnkKit>> listAsync(int pageNumber, int pageSize) {
        PagedQuery query = new PagedQuery(pageNumber, pageSize, null, null, false, Collections.emptyMap());
        return searchAsync(query);
    }

    public CompletableFuture<List<KnkKitAvailability>> getAvailableForUserAsync(int userId) {
        return queryApi.getAvailableForUser(userId);
    }

    public void invalidate(int id) {
        cache.invalidate(id);
    }

    public void invalidateAll() {
        cache.clear();
    }
}

package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.ports.api.ItemBlueprintsQueryApi;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class ItemBlueprintsDataAccess {

    private final ItemBlueprintCache cache;
    private final ItemBlueprintsQueryApi queryApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<Integer, KnkItemBlueprint> executor;

    private static class ItemBlueprintCache extends BaseCache<Integer, KnkItemBlueprint> {
        public ItemBlueprintCache(Duration ttl) {
            super(ttl);
        }

        public void put(KnkItemBlueprint itemBlueprint) {
            if (itemBlueprint != null && itemBlueprint.id() != null) {
                put(itemBlueprint.id(), itemBlueprint);
            }
        }
    }

    public ItemBlueprintsDataAccess(
            Duration ttl,
            ItemBlueprintsQueryApi queryApi
    ) {
        this(ttl, queryApi, DataAccessSettings.defaults());
    }

    public ItemBlueprintsDataAccess(
            Duration ttl,
            ItemBlueprintsQueryApi queryApi,
            DataAccessSettings settings
    ) {
        this.cache = new ItemBlueprintCache(ttl);
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(cache, this.settings.retryPolicy(), "ItemBlueprint");
    }

    public CompletableFuture<FetchResult<KnkItemBlueprint>> getByIdAsync(int id, FetchPolicy policy) {
        policy = settings.resolvePolicy(policy);

        return executor.fetchAsync(
                id,
                policy,
                () -> queryApi.getById(id).thenApply(itemBlueprint -> {
                    if (itemBlueprint != null) {
                        cache.put(itemBlueprint);
                    }
                    return itemBlueprint;
                })
        );
    }

    public CompletableFuture<FetchResult<KnkItemBlueprint>> getByIdAsync(int id) {
        return getByIdAsync(id, null);
    }

    public CompletableFuture<FetchResult<KnkItemBlueprint>> refreshAsync(int id) {
        return executor.fetchAsync(
                id,
                settings.resolvePolicy(FetchPolicy.API_ONLY),
                () -> queryApi.getById(id).thenApply(itemBlueprint -> {
                    if (itemBlueprint != null) {
                        cache.put(itemBlueprint);
                    }
                    return itemBlueprint;
                })
        );
    }

    public CompletableFuture<Page<KnkItemBlueprint>> searchAsync(PagedQuery query) {
        // Search results are ItemBlueprintListDto summaries (no description, grade, origins,
        // enchantments). They used to be put into the by-id cache, so a later getByIdAsync -
        // KitGrantPlacer, /knk itemblueprints give, the item catalogue - could build an item from
        // the summary and lose its lore (found in the menu follow-up 2026-09-26). Not cached now.
        return queryApi.search(query);
    }

    public CompletableFuture<Page<KnkItemBlueprint>> listAsync(int pageNumber, int pageSize) {
        PagedQuery query = new PagedQuery(pageNumber, pageSize, null, null, false, Collections.emptyMap());
        return searchAsync(query);
    }

    public void invalidate(int id) {
        cache.invalidate(id);
    }

    public void invalidateAll() {
        cache.clear();
    }
}

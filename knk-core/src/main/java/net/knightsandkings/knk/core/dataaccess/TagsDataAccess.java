package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.item.KnkTag;
import net.knightsandkings.knk.core.ports.api.TagsQueryApi;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class TagsDataAccess {

    private final TagCache cache;
    private final TagsQueryApi queryApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<Integer, KnkTag> executor;

    private static class TagCache extends BaseCache<Integer, KnkTag> {
        public TagCache(Duration ttl) {
            super(ttl);
        }

        public void put(KnkTag tag) {
            if (tag != null && tag.id() != null) {
                put(tag.id(), tag);
            }
        }
    }

    public TagsDataAccess(
            Duration ttl,
            TagsQueryApi queryApi
    ) {
        this(ttl, queryApi, DataAccessSettings.defaults());
    }

    public TagsDataAccess(
            Duration ttl,
            TagsQueryApi queryApi,
            DataAccessSettings settings
    ) {
        this.cache = new TagCache(ttl);
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(cache, this.settings.retryPolicy(), "Tag");
    }

    public CompletableFuture<FetchResult<KnkTag>> getByIdAsync(int id, FetchPolicy policy) {
        policy = settings.resolvePolicy(policy);

        return executor.fetchAsync(
                id,
                policy,
                () -> queryApi.getById(id).thenApply(tag -> {
                    if (tag != null) {
                        cache.put(tag);
                    }
                    return tag;
                })
        );
    }

    public CompletableFuture<FetchResult<KnkTag>> getByIdAsync(int id) {
        return getByIdAsync(id, null);
    }

    public CompletableFuture<FetchResult<KnkTag>> refreshAsync(int id) {
        return executor.fetchAsync(
                id,
                settings.resolvePolicy(FetchPolicy.API_ONLY),
                () -> queryApi.getById(id).thenApply(tag -> {
                    if (tag != null) {
                        cache.put(tag);
                    }
                    return tag;
                })
        );
    }

    public CompletableFuture<Page<KnkTag>> searchAsync(PagedQuery query) {
        return queryApi.search(query).thenApply(page -> {
            if (page != null && page.items() != null) {
                for (KnkTag tag : page.items()) {
                    cache.put(tag);
                }
            }
            return page;
        });
    }

    public CompletableFuture<Page<KnkTag>> listAsync(int pageNumber, int pageSize) {
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

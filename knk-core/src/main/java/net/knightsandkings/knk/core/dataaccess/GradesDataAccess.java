package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.item.KnkGrade;
import net.knightsandkings.knk.core.ports.api.GradesQueryApi;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class GradesDataAccess {

    private final GradeCache cache;
    private final GradesQueryApi queryApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<Integer, KnkGrade> executor;

    private static class GradeCache extends BaseCache<Integer, KnkGrade> {
        public GradeCache(Duration ttl) {
            super(ttl);
        }

        public void put(KnkGrade grade) {
            if (grade != null && grade.id() != null) {
                put(grade.id(), grade);
            }
        }
    }

    public GradesDataAccess(
            Duration ttl,
            GradesQueryApi queryApi
    ) {
        this(ttl, queryApi, DataAccessSettings.defaults());
    }

    public GradesDataAccess(
            Duration ttl,
            GradesQueryApi queryApi,
            DataAccessSettings settings
    ) {
        this.cache = new GradeCache(ttl);
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(cache, this.settings.retryPolicy(), "Grade");
    }

    public CompletableFuture<FetchResult<KnkGrade>> getByIdAsync(int id, FetchPolicy policy) {
        policy = settings.resolvePolicy(policy);

        return executor.fetchAsync(
                id,
                policy,
                () -> queryApi.getById(id).thenApply(grade -> {
                    if (grade != null) {
                        cache.put(grade);
                    }
                    return grade;
                })
        );
    }

    public CompletableFuture<FetchResult<KnkGrade>> getByIdAsync(int id) {
        return getByIdAsync(id, null);
    }

    public CompletableFuture<FetchResult<KnkGrade>> refreshAsync(int id) {
        return executor.fetchAsync(
                id,
                settings.resolvePolicy(FetchPolicy.API_ONLY),
                () -> queryApi.getById(id).thenApply(grade -> {
                    if (grade != null) {
                        cache.put(grade);
                    }
                    return grade;
                })
        );
    }

    public CompletableFuture<Page<KnkGrade>> searchAsync(PagedQuery query) {
        return queryApi.search(query).thenApply(page -> {
            if (page != null && page.items() != null) {
                for (KnkGrade grade : page.items()) {
                    cache.put(grade);
                }
            }
            return page;
        });
    }

    public CompletableFuture<Page<KnkGrade>> listAsync(int pageNumber, int pageSize) {
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

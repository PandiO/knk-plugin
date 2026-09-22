package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;
import net.knightsandkings.knk.core.ports.api.DomainCatalogQueryApi;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Id-based fetch and name-search browsing over Domains, keyed by Domain id (int) - distinct from
 * DomainsDataAccess, which is keyed by WorldGuard region id (String) and returns DomainRegionSummary
 * for the by-region/search-region-decisions responsibility. See this module's own class-level doc on
 * DomainCatalogQueryApi for why this is a separate class rather than a second responsibility bolted
 * onto DomainsDataAccess (docs/specs/items/IMPLEMENTATION_PLAN.md §6/§7.11).
 */
public class DomainCatalogDataAccess {

    private final DomainSummaryCache cache;
    private final DomainCatalogQueryApi queryApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<Integer, KnkDomainSummary> executor;

    private static class DomainSummaryCache extends BaseCache<Integer, KnkDomainSummary> {
        public DomainSummaryCache(Duration ttl) {
            super(ttl);
        }

        public void put(KnkDomainSummary domain) {
            if (domain != null && domain.id() != null) {
                put(domain.id(), domain);
            }
        }
    }

    public DomainCatalogDataAccess(
            Duration ttl,
            DomainCatalogQueryApi queryApi
    ) {
        this(ttl, queryApi, DataAccessSettings.defaults());
    }

    public DomainCatalogDataAccess(
            Duration ttl,
            DomainCatalogQueryApi queryApi,
            DataAccessSettings settings
    ) {
        this.cache = new DomainSummaryCache(ttl);
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(cache, this.settings.retryPolicy(), "DomainCatalog");
    }

    public CompletableFuture<FetchResult<KnkDomainSummary>> getByIdAsync(int id, FetchPolicy policy) {
        policy = settings.resolvePolicy(policy);

        return executor.fetchAsync(
                id,
                policy,
                () -> queryApi.getById(id).thenApply(domain -> {
                    if (domain != null) {
                        cache.put(domain);
                    }
                    return domain;
                })
        );
    }

    public CompletableFuture<FetchResult<KnkDomainSummary>> getByIdAsync(int id) {
        return getByIdAsync(id, null);
    }

    public CompletableFuture<FetchResult<KnkDomainSummary>> refreshAsync(int id) {
        return executor.fetchAsync(
                id,
                settings.resolvePolicy(FetchPolicy.API_ONLY),
                () -> queryApi.getById(id).thenApply(domain -> {
                    if (domain != null) {
                        cache.put(domain);
                    }
                    return domain;
                })
        );
    }

    public CompletableFuture<Page<KnkDomainSummary>> searchAsync(PagedQuery query) {
        return queryApi.search(query).thenApply(page -> {
            if (page != null && page.items() != null) {
                for (KnkDomainSummary domain : page.items()) {
                    cache.put(domain);
                }
            }
            return page;
        });
    }

    public CompletableFuture<Page<KnkDomainSummary>> listAsync(int pageNumber, int pageSize) {
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

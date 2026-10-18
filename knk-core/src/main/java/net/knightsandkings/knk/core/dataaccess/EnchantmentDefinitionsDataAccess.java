package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.enchantments.KnkEnchantmentDefinition;
import net.knightsandkings.knk.core.ports.api.EnchantmentDefinitionsQueryApi;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Data access gateway for EnchantmentDefinitions.
 *
 * Provides cache-first getById and search/list retrieval with cache warming.
 */
public class EnchantmentDefinitionsDataAccess {

    private final EnchantmentDefinitionCache cache;
    private final EnchantmentDefinitionsQueryApi queryApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<Integer, KnkEnchantmentDefinition> executor;

    private static class EnchantmentDefinitionCache extends BaseCache<Integer, KnkEnchantmentDefinition> {
        public EnchantmentDefinitionCache(Duration ttl) {
            super(ttl);
        }

        public void put(KnkEnchantmentDefinition definition) {
            if (definition != null && definition.id() != null) {
                put(definition.id(), definition);
            }
        }
    }

    public EnchantmentDefinitionsDataAccess(
            Duration ttl,
            EnchantmentDefinitionsQueryApi queryApi
    ) {
        this(ttl, queryApi, DataAccessSettings.defaults());
    }

    public EnchantmentDefinitionsDataAccess(
            Duration ttl,
            EnchantmentDefinitionsQueryApi queryApi,
            DataAccessSettings settings
    ) {
        this.cache = new EnchantmentDefinitionCache(ttl);
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(cache, this.settings, "EnchantmentDefinition");
    }

    public CompletableFuture<FetchResult<KnkEnchantmentDefinition>> getByIdAsync(int id, FetchPolicy policy) {
        policy = settings.resolvePolicy(policy);

        return executor.fetchAsync(
                id,
                policy,
                () -> queryApi.getById(id).thenApply(definition -> {
                    if (definition != null) {
                        cache.put(definition);
                    }
                    return definition;
                })
        );
    }

    public CompletableFuture<FetchResult<KnkEnchantmentDefinition>> getByIdAsync(int id) {
        return getByIdAsync(id, null);
    }

    public CompletableFuture<FetchResult<KnkEnchantmentDefinition>> refreshAsync(int id) {
        return executor.fetchAsync(
                id,
                settings.resolvePolicy(FetchPolicy.API_ONLY),
                () -> queryApi.getById(id).thenApply(definition -> {
                    if (definition != null) {
                        cache.put(definition);
                    }
                    return definition;
                })
        );
    }

    public CompletableFuture<Page<KnkEnchantmentDefinition>> searchAsync(PagedQuery query) {
        return queryApi.search(query).thenApply(page -> {
            if (page != null && page.items() != null) {
                for (KnkEnchantmentDefinition definition : page.items()) {
                    cache.put(definition);
                }
            }
            return page;
        });
    }

    public CompletableFuture<Page<KnkEnchantmentDefinition>> listAsync(int pageNumber, int pageSize) {
        PagedQuery query = new PagedQuery(pageNumber, pageSize, null, null, false, java.util.Collections.emptyMap());
        return searchAsync(query);
    }

    public void invalidate(int id) {
        cache.invalidate(id);
    }

    public void invalidateAll() {
        cache.clear();
    }
}

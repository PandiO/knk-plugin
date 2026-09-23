package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplateSummary;
import net.knightsandkings.knk.core.ports.api.MenuTemplatesQueryApi;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Cache-first gateway over MenuTemplates (IMPLEMENTATION_PLAN.md Phase 1),
 * following the same fetch-policy/cache/retry conventions as
 * {@link ItemBlueprintsDataAccess}.
 * <p>
 * One deliberate deviation from that precedent: the cache here is keyed by
 * the template's stable string {@code key} (e.g. "kits.overview"), not its
 * numeric id - IMPLEMENTATION_PLAN.md names the key as "the stable lookup
 * key the plugin resolves by", which is how the Phase 2 rendering engine
 * will actually look menus up to open them. {@link #getByIdAsync(int)} and
 * {@link #listAllAsync()} bypass the cache and go straight to the API
 * (mirroring how {@code searchAsync} bypasses the id-cache in
 * {@link ItemBlueprintsDataAccess}), writing a full template's result
 * through into the key-cache afterward when there is one to write.
 */
public class MenuTemplatesDataAccess {

    private final MenuTemplateCache cache;
    private final MenuTemplatesQueryApi queryApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<String, KnkMenuTemplate> executor;

    private static class MenuTemplateCache extends BaseCache<String, KnkMenuTemplate> {
        public MenuTemplateCache(Duration ttl) {
            super(ttl);
        }

        public void put(KnkMenuTemplate template) {
            if (template != null && template.key() != null) {
                put(template.key(), template);
            }
        }
    }

    public MenuTemplatesDataAccess(
            Duration ttl,
            MenuTemplatesQueryApi queryApi
    ) {
        this(ttl, queryApi, DataAccessSettings.defaults());
    }

    public MenuTemplatesDataAccess(
            Duration ttl,
            MenuTemplatesQueryApi queryApi,
            DataAccessSettings settings
    ) {
        this.cache = new MenuTemplateCache(ttl);
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(cache, this.settings.retryPolicy(), "MenuTemplate");
    }

    public CompletableFuture<FetchResult<KnkMenuTemplate>> getByKeyAsync(String key, FetchPolicy policy) {
        policy = settings.resolvePolicy(policy);

        return executor.fetchAsync(
                key,
                policy,
                () -> queryApi.getByKey(key).thenApply(template -> {
                    if (template != null) {
                        cache.put(template);
                    }
                    return template;
                })
        );
    }

    public CompletableFuture<FetchResult<KnkMenuTemplate>> getByKeyAsync(String key) {
        return getByKeyAsync(key, null);
    }

    public CompletableFuture<KnkMenuTemplate> getByIdAsync(int id) {
        return queryApi.getById(id).thenApply(template -> {
            if (template != null) {
                cache.put(template);
            }
            return template;
        });
    }

    public CompletableFuture<KnkMenuTemplate> refreshAsync(String key) {
        return executor.fetchAsync(
                key,
                settings.resolvePolicy(FetchPolicy.API_ONLY),
                () -> queryApi.getByKey(key).thenApply(template -> {
                    if (template != null) {
                        cache.put(template);
                    }
                    return template;
                })
        ).thenApply(result -> result.orElse(null));
    }

    public CompletableFuture<List<KnkMenuTemplateSummary>> listAllAsync() {
        return queryApi.listAll();
    }

    public void invalidate(String key) {
        cache.invalidate(key);
    }

    public void invalidateAll() {
        cache.clear();
    }
}

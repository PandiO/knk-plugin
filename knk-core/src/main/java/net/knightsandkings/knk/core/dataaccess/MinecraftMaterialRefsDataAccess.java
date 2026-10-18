package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.material.KnkMinecraftMaterialRef;
import net.knightsandkings.knk.core.ports.api.MinecraftMaterialRefsQueryApi;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class MinecraftMaterialRefsDataAccess {

    private final MinecraftMaterialRefCache cache;
    private final MinecraftMaterialRefsQueryApi queryApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<Integer, KnkMinecraftMaterialRef> executor;

    private static class MinecraftMaterialRefCache extends BaseCache<Integer, KnkMinecraftMaterialRef> {
        public MinecraftMaterialRefCache(Duration ttl) {
            super(ttl);
        }

        public void put(KnkMinecraftMaterialRef materialRef) {
            if (materialRef != null && materialRef.id() != null) {
                put(materialRef.id(), materialRef);
            }
        }
    }

    public MinecraftMaterialRefsDataAccess(
            Duration ttl,
            MinecraftMaterialRefsQueryApi queryApi
    ) {
        this(ttl, queryApi, DataAccessSettings.defaults());
    }

    public MinecraftMaterialRefsDataAccess(
            Duration ttl,
            MinecraftMaterialRefsQueryApi queryApi,
            DataAccessSettings settings
    ) {
        this.cache = new MinecraftMaterialRefCache(ttl);
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(cache, this.settings, "MinecraftMaterialRef");
    }

    public CompletableFuture<FetchResult<KnkMinecraftMaterialRef>> getByIdAsync(int id, FetchPolicy policy) {
        policy = settings.resolvePolicy(policy);

        return executor.fetchAsync(
                id,
                policy,
                () -> queryApi.getById(id).thenApply(materialRef -> {
                    if (materialRef != null) {
                        cache.put(materialRef);
                    }
                    return materialRef;
                })
        );
    }

    public CompletableFuture<FetchResult<KnkMinecraftMaterialRef>> getByIdAsync(int id) {
        return getByIdAsync(id, null);
    }

    public CompletableFuture<FetchResult<KnkMinecraftMaterialRef>> refreshAsync(int id) {
        return executor.fetchAsync(
                id,
                settings.resolvePolicy(FetchPolicy.API_ONLY),
                () -> queryApi.getById(id).thenApply(materialRef -> {
                    if (materialRef != null) {
                        cache.put(materialRef);
                    }
                    return materialRef;
                })
        );
    }

    public void invalidate(int id) {
        cache.invalidate(id);
    }

    public void invalidateAll() {
        cache.clear();
    }
}

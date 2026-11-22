package net.knightsandkings.knk.paper.realtime;

import java.util.function.IntConsumer;

import net.knightsandkings.knk.core.realtime.Coalescer;
import net.knightsandkings.knk.core.realtime.EntityChangeRouter;

/**
 * Landmark-domain DESIGN § 7.2 item 2, LD22 (PandiO/knk-workspace#985, A16): a {@code Landmark} change in the API
 * ({@code EntityChanged} with type {@code Landmark}, {@code id} the domain id, {@code key} its own region or null)
 * reaches the plugin like the other domains' changes: the domain catalogue drops it (or everything without an id), the
 * region cache is cleared (its region, sub-regions or parent may have changed), and once per burst of changes (the
 * coalescer) the navigation model is refreshed, so {@code /navigate} sees a rename or a new area within seconds, and
 * the landmark message settings are read again. Each part may be missing (null): that cache is not on this server.
 */
public final class LandmarkRealtime {

    /** The API's entity type name. */
    public static final String TYPE = "Landmark";
    /** The coalescer's key: changes within its delay share one refresh. */
    public static final String KEY = "landmarks";

    private LandmarkRealtime() {
    }

    /**
     * @param invalidateCatalog     drops one domain from the domain catalogue; null: no catalogue
     * @param invalidateCatalogAll  drops the whole catalogue (a change without an id); null: no catalogue
     * @param clearRegionCache      clears the region → domain cache; null: none
     * @param refreshModel          refreshes the navigation model; null: none
     * @param refreshMessages       reads the landmark message settings again; null: none
     */
    public static void register(EntityChangeRouter router, Coalescer coalescer, IntConsumer invalidateCatalog,
                                Runnable invalidateCatalogAll, Runnable clearRegionCache, Runnable refreshModel,
                                Runnable refreshMessages) {
        if (invalidateCatalog != null && invalidateCatalogAll != null) {
            router.on(TYPE, (id, key, op) -> {
                if (id == null) {
                    invalidateCatalogAll.run();
                } else {
                    invalidateCatalog.accept(id);
                }
            });
        }
        if (clearRegionCache != null) {
            router.on(TYPE, (id, key, op) -> clearRegionCache.run());
        }
        if (refreshModel != null || refreshMessages != null) {
            router.on(TYPE, (id, key, op) -> coalescer.request(KEY, () -> {
                try {
                    if (refreshModel != null) {
                        refreshModel.run();
                    }
                } finally {
                    if (refreshMessages != null) {
                        refreshMessages.run();
                    }
                }
            }));
        }
    }
}

package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Content port CP4 ({@code docs/specs/inventory-menu/CONTENT_PORT_PLAN.md} §6): {@code items.catalog}
 * is engine-only (the {@code catalog.itemblueprints} source + search), except for the header's
 * "total items" line - the engine exposes no total for a content section, so this feature
 * registers root {@code itemsCatalog} → {@link ItemsCatalogView}. Providers run on the main
 * thread and must not do I/O: the count is fetched in the background (a one-row page of
 * {@code ItemBlueprintsDataAccess.searchAsync}, whose {@code totalCount} is the catalogue size)
 * at registration and again whenever it is older than {@link #REFRESH_MILLIS} when read; until
 * the first answer arrives the line is omitted (E8).
 */
public final class ItemsCatalogMenuFeature implements MenuFeature {

    public static final String MENU_KEY = "items.catalog";
    public static final String ROOT = "itemsCatalog";
    static final long REFRESH_MILLIS = 60_000L;

    private static final Logger LOGGER = Logger.getLogger(ItemsCatalogMenuFeature.class.getName());

    private final ItemBlueprintsDataAccess itemBlueprints;
    private final Clock clock;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Integer totalCount;
    private volatile long countedAtMillis;

    public ItemsCatalogMenuFeature(ItemBlueprintsDataAccess itemBlueprints, Clock clock) {
        this.itemBlueprints = itemBlueprints;
        this.clock = clock;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.variables().register(ROOT, ItemsCatalogView.class, (player, ctx) -> view());
        refreshCount();
    }

    /** Main thread, no I/O: the last known count; starts a background refresh when it is stale. */
    ItemsCatalogView view() {
        if (totalCount == null || clock.millis() - countedAtMillis >= REFRESH_MILLIS) {
            refreshCount();
        }
        return new ItemsCatalogView(totalCount);
    }

    void refreshCount() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            itemBlueprints.searchAsync(new PagedQuery(1, 1, null, null, false, Map.of()))
                    .whenComplete((page, ex) -> {
                        if (ex != null) {
                            LOGGER.log(Level.FINE, "items.catalog: couldn't count item blueprints", ex);
                        } else if (page != null) {
                            totalCount = page.totalCount();
                            countedAtMillis = clock.millis();
                        }
                        refreshing.set(false);
                    });
        } catch (RuntimeException e) {
            refreshing.set(false);
            LOGGER.log(Level.FINE, "items.catalog: couldn't count item blueprints", e);
        }
    }

    /** The {@code itemsCatalog} root. */
    public static final class ItemsCatalogView {
        private final Integer totalCount;

        ItemsCatalogView(Integer totalCount) {
            this.totalCount = totalCount;
        }

        /** "&7Items in the catalogue: &f123", or null (line dropped) while unknown. */
        public String getTotalLine() {
            return totalCount != null ? "&7Items in the catalogue: &f" + totalCount : null;
        }

        public Integer getTotalCount() {
            return totalCount;
        }
    }
}

package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.dataaccess.CachedList;
import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkItemCategory;
import net.knightsandkings.knk.core.ports.api.CategoriesQueryApi;
import net.knightsandkings.knk.paper.kit.KitGrantPlacer;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Content port CP4 + menu follow-up 2026-09-26: {@code items.catalog}.
 * <ul>
 *   <li>Row source {@code items.catalog} → {@link CatalogItemRow}: one page of
 *       {@code ItemBlueprintsDataAccess.searchAsync} (search text and the {@code Category} filter
 *       are the section's query), each blueprint then read in full by id (cache-first) and shown
 *       as the ItemStack a player would receive ({@link CatalogItemRow}).</li>
 *   <li>Root {@code itemsCatalog} → {@link ItemsCatalogView}: the catalogue size (background
 *       count, never I/O on the main thread) and {@code getCategoryValues} - the comma list the
 *       template's {@code menu.filter.cycle} button cycles through (live, from
 *       {@code GET /api/Categories}, cached).</li>
 * </ul>
 */
public final class ItemsCatalogMenuFeature implements MenuFeature {

    public static final String MENU_KEY = "items.catalog";
    public static final String ROOT = "itemsCatalog";
    public static final String ROWS_SOURCE = "items.catalog";
    public static final String CATEGORY_FACET = "Category";
    static final long REFRESH_MILLIS = 60_000L;

    private static final Logger LOGGER = Logger.getLogger(ItemsCatalogMenuFeature.class.getName());

    private final ItemBlueprintsDataAccess itemBlueprints;
    private final MinecraftMaterialRefsDataAccess materialRefs;
    private final CachedList<KnkItemCategory> categories;
    private final Clock clock;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Integer totalCount;
    private volatile long countedAtMillis;

    public ItemsCatalogMenuFeature(ItemBlueprintsDataAccess itemBlueprints, MinecraftMaterialRefsDataAccess materialRefs,
                                   CategoriesQueryApi categoriesQueryApi, Clock clock) {
        this.itemBlueprints = itemBlueprints;
        this.materialRefs = materialRefs;
        this.categories = new CachedList<>(categoriesQueryApi::listAll, Duration.ofMinutes(10), clock);
        this.clock = clock;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.variables().register(ROOT, ItemsCatalogView.class, (player, ctx) -> view());
        registries.contentSources().registerRows(ROWS_SOURCE, CatalogItemRow.class, (context, params, query) -> fetchRows(query));
        refreshCount();
        categories.getAsync().exceptionally(ex -> {
            LOGGER.log(Level.FINE, "items.catalog: couldn't load categories", ex);
            return List.of();
        });
    }

    // ===== rows =====

    CompletableFuture<Page<CatalogItemRow>> fetchRows(PagedQuery query) {
        return itemBlueprints.searchAsync(query).thenCompose(page -> {
            List<KnkItemBlueprint> summaries = page != null && page.items() != null ? page.items() : List.of();
            List<CompletableFuture<CatalogItemRow>> rows = new ArrayList<>(summaries.size());
            for (KnkItemBlueprint summary : summaries) {
                rows.add(fullRow(summary));
            }
            return CompletableFuture.allOf(rows.toArray(new CompletableFuture[0])).thenApply(done -> {
                List<CatalogItemRow> built = rows.stream().map(CompletableFuture::join).toList();
                int total = page != null ? page.totalCount() : built.size();
                if (query.filters() == null || query.filters().isEmpty()) {
                    if (query.searchTerm() == null || query.searchTerm().isBlank()) {
                        totalCount = total;
                        countedAtMillis = clock.millis();
                    }
                }
                return new Page<>(built, total, query.pageNumber(), query.pageSize());
            });
        });
    }

    /** The full blueprint (by id, cache-first) + its icon material key; the summary when the full read fails. */
    private CompletableFuture<CatalogItemRow> fullRow(KnkItemBlueprint summary) {
        CompletableFuture<KnkItemBlueprint> full = summary.id() == null
                ? CompletableFuture.completedFuture(summary)
                : itemBlueprints.getByIdAsync(summary.id())
                        .thenApply(result -> valueOr(result, summary))
                        .exceptionally(ex -> summary);
        return full.thenCompose(blueprint -> KitGrantPlacer.resolveMaterialNamespaceKey(blueprint, materialRefs)
                .exceptionally(ex -> blueprint.iconNamespaceKey())
                .thenApply(key -> new CatalogItemRow(blueprint, key)));
    }

    private static KnkItemBlueprint valueOr(FetchResult<KnkItemBlueprint> result, KnkItemBlueprint fallback) {
        return result != null ? result.value().orElse(fallback) : fallback;
    }

    // ===== root =====

    /** Main thread, no I/O: the last known count and category list; starts background refreshes when stale. */
    ItemsCatalogView view() {
        if (totalCount == null || clock.millis() - countedAtMillis >= REFRESH_MILLIS) {
            refreshCount();
        }
        categories.getAsync().exceptionally(ex -> List.of());
        return new ItemsCatalogView(totalCount, categories.cachedOrEmpty());
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
        private final List<KnkItemCategory> categories;

        ItemsCatalogView(Integer totalCount, List<KnkItemCategory> categories) {
            this.totalCount = totalCount;
            this.categories = categories;
        }

        /** "&7Items in the catalogue: &f123", or null (line dropped) while unknown. */
        public String getTotalLine() {
            return totalCount != null ? "&7Items in the catalogue: &f" + totalCount : null;
        }

        public Integer getTotalCount() {
            return totalCount;
        }

        /**
         * Every category name, alphabetically, comma-separated - the values of the catalogue's
         * {@code menu.filter.cycle} button (a name containing a comma can't be cycled and is left
         * out). Empty until the category list has loaded.
         */
        public String getCategoryValues() {
            return categories.stream()
                    .map(KnkItemCategory::name)
                    .filter(name -> !name.contains(","))
                    .distinct()
                    .sorted((a, b) -> a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT)))
                    .collect(Collectors.joining(","));
        }
    }
}

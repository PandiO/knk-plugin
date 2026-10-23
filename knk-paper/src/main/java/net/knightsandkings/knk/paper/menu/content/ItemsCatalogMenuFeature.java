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
import java.util.LinkedHashMap;
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
 *       as the ItemStack a player would receive ({@link CatalogItemRow}). KNG-231: a built page
 *       is kept for {@link #PAGE_TTL_MILLIS} (shared by every player), the pages before and after
 *       it are built in the background as soon as it is shown, and a page past the end is answered
 *       from the known total - so paging usually needs no API call at all.</li>
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
    /** KNG-231: how long a built page is reused; the blueprint and material caches use 60 s too. */
    static final long PAGE_TTL_MILLIS = 60_000L;
    /** KNG-231: built pages kept at most (every search/filter/page combination counts). */
    static final int MAX_CACHED_PAGES = 64;

    private static final Logger LOGGER = Logger.getLogger(ItemsCatalogMenuFeature.class.getName());

    private final ItemBlueprintsDataAccess itemBlueprints;
    private final MinecraftMaterialRefsDataAccess materialRefs;
    private final CachedList<KnkItemCategory> categories;
    private final Clock clock;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Integer totalCount;
    private volatile long countedAtMillis;
    /** KNG-231: built (or still building) pages by query, oldest first. Guarded by itself. */
    private final Map<PagedQuery, CachedPage> pages = new LinkedHashMap<>(16, 0.75f, true);

    private record CachedPage(CompletableFuture<Page<CatalogItemRow>> page, long builtAtMillis) {
    }

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

    /** A page for the menu: cached or built, and its neighbours prefetched once it is there. */
    CompletableFuture<Page<CatalogItemRow>> fetchRows(PagedQuery query) {
        CompletableFuture<Page<CatalogItemRow>> page = cachedOrBuild(query);
        page.thenAccept(built -> prefetchAround(query, built.totalCount()));
        return page;
    }

    private CompletableFuture<Page<CatalogItemRow>> cachedOrBuild(PagedQuery query) {
        long now = clock.millis();
        CompletableFuture<Page<CatalogItemRow>> built;
        synchronized (pages) {
            CachedPage cached = pages.get(query);
            if (cached != null && now - cached.builtAtMillis() < PAGE_TTL_MILLIS
                    && !cached.page().isCompletedExceptionally()) {
                return cached.page();
            }
            Integer knownTotal = knownTotal(query, now);
            if (knownTotal != null && query.pageNumber() > pageCount(knownTotal, query.pageSize())) {
                // Past the end (the menu steps unclamped and wraps once it sees the total): no fetch.
                return CompletableFuture.completedFuture(new Page<>(List.of(), knownTotal, query.pageNumber(), query.pageSize()));
            }
            built = new CompletableFuture<>();
            pages.put(query, new CachedPage(built, now));
            while (pages.size() > MAX_CACHED_PAGES) {
                pages.remove(pages.keySet().iterator().next());
            }
        }
        CompletableFuture<Page<CatalogItemRow>> request;
        try {
            request = buildRows(query);
        } catch (RuntimeException e) {
            request = CompletableFuture.failedFuture(e);
        }
        request.whenComplete((page, ex) -> {
            if (ex != null) {
                synchronized (pages) {
                    CachedPage entry = pages.get(query);
                    if (entry != null && entry.page() == built) {
                        pages.remove(query);
                    }
                }
                built.completeExceptionally(ex);
            } else {
                built.complete(page);
            }
        });
        return built;
    }

    /** The total of a fresh cached page with the same search, filters and page size, if any. Holds {@link #pages}. */
    private Integer knownTotal(PagedQuery query, long now) {
        for (Map.Entry<PagedQuery, CachedPage> entry : pages.entrySet()) {
            PagedQuery other = entry.getKey();
            CompletableFuture<Page<CatalogItemRow>> page = entry.getValue().page();
            if (other.pageSize() == query.pageSize() && sameFamily(other, query)
                    && now - entry.getValue().builtAtMillis() < PAGE_TTL_MILLIS
                    && page.isDone() && !page.isCompletedExceptionally()) {
                return page.join().totalCount();
            }
        }
        return null;
    }

    private static boolean sameFamily(PagedQuery a, PagedQuery b) {
        return java.util.Objects.equals(a.searchTerm(), b.searchTerm())
                && java.util.Objects.equals(a.sortBy(), b.sortBy())
                && a.sortDescending() == b.sortDescending()
                && java.util.Objects.equals(a.filters(), b.filters());
    }

    private static int pageCount(int total, int pageSize) {
        return pageSize > 0 ? (int) Math.ceil(total / (double) pageSize) : 0;
    }

    /**
     * KNG-231: builds the next page (wrapping to page 1 after the last, like the pager) and the
     * previous one in the background. Not the last page from page 1: opening the catalogue would
     * then cost two pages of API calls for one rarely wanted.
     */
    private void prefetchAround(PagedQuery query, int total) {
        int pageCount = pageCount(total, query.pageSize());
        int current = query.pageNumber();
        if (pageCount <= 1 || current < 1 || current > pageCount) {
            return;
        }
        int next = current % pageCount + 1;
        List<Integer> targets = current > 1 && current - 1 != next ? List.of(next, current - 1) : List.of(next);
        for (int target : targets) {
            cachedOrBuild(withPage(query, target)).exceptionally(ex -> {
                LOGGER.log(Level.FINE, "items.catalog: couldn't prefetch page " + target, ex);
                return null;
            });
        }
    }

    private static PagedQuery withPage(PagedQuery query, int pageNumber) {
        return new PagedQuery(pageNumber, query.pageSize(), query.searchTerm(), query.sortBy(), query.sortDescending(),
                query.filters());
    }

    /** One page from the API: the search, then every row's full blueprint and icon material. */
    private CompletableFuture<Page<CatalogItemRow>> buildRows(PagedQuery query) {
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

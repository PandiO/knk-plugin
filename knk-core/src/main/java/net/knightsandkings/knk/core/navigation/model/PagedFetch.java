package net.knightsandkings.knk.core.navigation.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;

/**
 * Every item of a paged API search, page by page (the same paging as {@code NavigationDestinations.allPages}: 200 per
 * page, at most 25 pages, sorted by name). The domain search takes the page size as given (knk-web-api
 * {@code DomainRepository.SearchAsync}, checked for PandiO/knk-workspace#103), so 200 is not capped.
 */
public final class PagedFetch {

    public static final int PAGE_SIZE = 200;
    public static final int MAX_PAGES = 25;

    private PagedFetch() {
    }

    public static <T> CompletableFuture<List<T>> allPages(Function<PagedQuery, CompletableFuture<Page<T>>> search) {
        return page(search, 1, new ArrayList<>());
    }

    private static <T> CompletableFuture<List<T>> page(Function<PagedQuery, CompletableFuture<Page<T>>> search,
                                                       int pageNumber, List<T> out) {
        return search.apply(new PagedQuery(pageNumber, PAGE_SIZE, null, "name", false, Map.of()))
            .thenCompose(page -> {
                if (page == null || page.items() == null || page.items().isEmpty()) {
                    return CompletableFuture.completedFuture(out);
                }
                out.addAll(page.items());
                boolean more = out.size() < page.totalCount() && page.items().size() >= PAGE_SIZE && pageNumber < MAX_PAGES;
                return more ? page(search, pageNumber + 1, out) : CompletableFuture.completedFuture(out);
            });
    }
}

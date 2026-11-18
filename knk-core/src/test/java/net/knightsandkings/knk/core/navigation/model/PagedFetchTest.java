package net.knightsandkings.knk.core.navigation.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import org.junit.jupiter.api.Test;

/** One paged search into one list, as the catalogue pages today (200 a page, at most 25 pages, by name). */
class PagedFetchTest {

    @Test
    void everyPageIsReadUntilTheTotalIsReached() {
        List<Integer> all = IntStream.range(0, 450).boxed().toList();
        List<PagedQuery> asked = new ArrayList<>();

        List<Integer> fetched = PagedFetch.allPages((PagedQuery q) -> {
            asked.add(q);
            int from = (q.pageNumber() - 1) * q.pageSize();
            List<Integer> items = all.subList(Math.min(from, all.size()), Math.min(from + q.pageSize(), all.size()));
            return CompletableFuture.completedFuture(new Page<>(items, all.size(), q.pageNumber(), q.pageSize()));
        }).join();

        assertEquals(all, fetched);
        assertEquals(List.of(1, 2, 3), asked.stream().map(PagedQuery::pageNumber).toList());
        assertEquals(200, asked.get(0).pageSize());
        assertEquals("name", asked.get(0).sortBy());
    }

    @Test
    void anEmptyOrMissingPageEndsTheFetch() {
        assertEquals(List.of(), PagedFetch.<Integer>allPages(q -> CompletableFuture.completedFuture(null)).join());
        assertEquals(List.of(), PagedFetch.<Integer>allPages(
            q -> CompletableFuture.completedFuture(new Page<>(List.of(), 5, 1, 200))).join());
    }

    @Test
    void atMostTwentyFivePagesAreRead() {
        List<Integer> fetched = PagedFetch.allPages((PagedQuery q) -> CompletableFuture.completedFuture(
            new Page<>(IntStream.range(0, 200).boxed().toList(), 1_000_000, q.pageNumber(), 200))).join();

        assertEquals(25 * 200, fetched.size());
    }
}

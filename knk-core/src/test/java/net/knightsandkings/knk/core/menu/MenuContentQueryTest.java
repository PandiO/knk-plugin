package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IMPLEMENTATION_PLAN.md Phase 5 / DESIGN_REVIEW.md §2.3: search text and
 * filter facet values compose through this one record rather than two
 * independent mechanisms.
 */
class MenuContentQueryTest {

    @Test
    void emptyIsEmpty() {
        assertTrue(MenuContentQuery.EMPTY.isEmpty());
    }

    @Test
    void blankSearchTextAndNoFiltersIsEmpty() {
        assertTrue(new MenuContentQuery("   ", Map.of()).isEmpty());
        assertTrue(new MenuContentQuery(null, Map.of()).isEmpty());
    }

    @Test
    void nonBlankSearchTextIsNotEmpty() {
        assertFalse(new MenuContentQuery("apple", Map.of()).isEmpty());
    }

    @Test
    void anyFilterValueIsNotEmpty() {
        assertFalse(new MenuContentQuery(null, Map.of("Category", "Fruit")).isEmpty());
    }

    @Test
    void withSearchTextReplacesOnlySearchText() {
        MenuContentQuery query = MenuContentQuery.EMPTY.withFilterValue("Category", "Fruit");

        MenuContentQuery updated = query.withSearchText("apple");

        assertEquals("apple", updated.searchText());
        assertEquals(Map.of("Category", "Fruit"), updated.filterValues());
    }

    @Test
    void withFilterValueSetsAndClearsIndependently() {
        MenuContentQuery query = MenuContentQuery.EMPTY
                .withFilterValue("Category", "Fruit")
                .withFilterValue("Grade", "A");

        assertEquals(Map.of("Category", "Fruit", "Grade", "A"), query.filterValues());

        MenuContentQuery cleared = query.withFilterValue("Category", null);

        assertEquals(Map.of("Grade", "A"), cleared.filterValues());
    }

    @Test
    void searchTextAndFiltersComposeIntoOneNonEmptyQuery() {
        MenuContentQuery query = new MenuContentQuery(null, Map.of()).withSearchText("berry").withFilterValue("Category", "Berry");

        assertFalse(query.isEmpty());
        assertEquals("berry", query.searchText());
        assertEquals("Berry", query.filterValues().get("Category"));
    }
}

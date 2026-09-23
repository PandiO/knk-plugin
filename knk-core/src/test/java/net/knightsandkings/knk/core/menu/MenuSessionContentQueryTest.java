package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IMPLEMENTATION_PLAN.md Phase 5: {@link MenuSession#getContentQuery}/
 * {@link MenuSession#setContentQuery} are the "one shared active content
 * predicate" per-section state DESIGN_REVIEW.md §2.3 calls for.
 */
class MenuSessionContentQueryTest {

    private static MenuSession session() {
        return new MenuSessionRegistry().open(UUID.randomUUID());
    }

    @Test
    void unsetSectionReturnsEmptyQuery() {
        MenuSession session = session();

        assertEquals(MenuContentQuery.EMPTY, session.getContentQuery(1));
    }

    @Test
    void nullSectionIdReturnsEmptyQuery() {
        MenuSession session = session();

        assertEquals(MenuContentQuery.EMPTY, session.getContentQuery(null));
    }

    @Test
    void setContentQueryIsRetrievableByTheSameSectionId() {
        MenuSession session = session();
        MenuContentQuery query = new MenuContentQuery("apple", Map.of("Category", "Fruit"));

        session.setContentQuery(1, query);

        assertEquals(query, session.getContentQuery(1));
    }

    @Test
    void differentSectionsHaveIndependentQueries() {
        MenuSession session = session();

        session.setContentQuery(1, new MenuContentQuery("apple", Map.of()));
        session.setContentQuery(2, new MenuContentQuery("berry", Map.of()));

        assertEquals("apple", session.getContentQuery(1).searchText());
        assertEquals("berry", session.getContentQuery(2).searchText());
    }

    @Test
    void settingAnEmptyQueryClearsIt() {
        MenuSession session = session();
        session.setContentQuery(1, new MenuContentQuery("apple", Map.of()));

        session.setContentQuery(1, MenuContentQuery.EMPTY);

        assertEquals(MenuContentQuery.EMPTY, session.getContentQuery(1));
    }

    /** DESIGN_REVIEW.md §1 names "a filter/search updated" as an explicit ON_DIRTY trigger. */
    @Test
    void settingAContentQueryMarksTheSessionDirty() {
        MenuSession session = session();
        assertFalse(session.isDirty());

        session.setContentQuery(1, new MenuContentQuery("apple", Map.of()));

        assertTrue(session.isDirty());
    }
}

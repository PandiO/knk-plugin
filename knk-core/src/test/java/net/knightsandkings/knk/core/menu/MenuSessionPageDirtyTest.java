package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KNG-230: the seeded pager lore ({@code Page $section.getPage$/$section.getPageCount$}, OnDirty)
 * kept reading "Page 1/N" after paging, because no page change marked the session dirty.
 */
class MenuSessionPageDirtyTest {

    private static final int SECTION = 7;
    private static final KnkVariableBinding PAGER_LORE = new KnkVariableBinding(
            1, "Lore", 0, "&7Page &a$section.getPage$&7/&a$section.getPageCount$", "OnDirty", null);

    private static MenuSession session() {
        return new MenuSession(UUID.randomUUID());
    }

    /** One render pass as {@code MenuRenderer} does it: resolve with the session's page, then clear dirty. */
    private static String renderPagerLore(MenuSession session, int totalPages, long tick) {
        SectionView view = new SectionView("Items", session.getPage(SECTION), totalPages);
        String lore = VariableResolver.resolve(PAGER_LORE, session, Map.of("section", view), tick);
        session.clearDirty();
        return lore;
    }

    @Test
    void pagerLoreFollowsNextPreviousWrapAndFirstPage() {
        MenuSession session = session();
        assertEquals("&7Page &a1&7/&a5", renderPagerLore(session, 5, 0L));

        session.stepPage(SECTION, 1);
        assertEquals("&7Page &a2&7/&a5", renderPagerLore(session, 5, 1L));

        session.nextPage(SECTION, 5);
        assertEquals("&7Page &a3&7/&a5", renderPagerLore(session, 5, 2L));

        session.previousPage(SECTION, 5);
        assertEquals("&7Page &a2&7/&a5", renderPagerLore(session, 5, 3L));

        session.firstPage(SECTION);
        session.previousPage(SECTION, 5);
        assertEquals("&7Page &a5&7/&a5", renderPagerLore(session, 5, 4L), "previous from page 1 wraps to the last page");

        session.firstPage(SECTION);
        assertEquals("&7Page &a1&7/&a5", renderPagerLore(session, 5, 5L));
    }

    @Test
    void contentSourceWrapAfterUnclampedStepIsShown() {
        MenuSession session = session();
        session.setPage(SECTION, 4);
        renderPagerLore(session, 5, 0L);

        // MenuService steps unclamped; MenuRenderer wraps once the fetch reveals the total.
        session.stepPage(SECTION, 1);
        session.setPage(SECTION, Math.floorMod(session.getPage(SECTION), 5));

        assertEquals("&7Page &a1&7/&a5", renderPagerLore(session, 5, 1L));
    }

    @Test
    void everyPageChangeMarksDirtyButSettingTheSamePageDoesNot() {
        MenuSession session = session();

        session.setPage(SECTION, 0);
        assertFalse(session.isDirty(), "page 0 is the default: nothing changed");

        session.stepPage(SECTION, 1);
        assertTrue(session.isDirty());
        session.clearDirty();

        session.setPage(SECTION, 1);
        assertFalse(session.isDirty(), "same page again");

        session.nextPage(SECTION, 3);
        assertTrue(session.isDirty());
        session.clearDirty();

        session.previousPage(SECTION, 3);
        assertTrue(session.isDirty());
        session.clearDirty();

        session.firstPage(SECTION);
        assertTrue(session.isDirty());
    }

    @Test
    void searchStillResetsThePageAndMarksDirty() {
        MenuSession session = session();
        session.setPage(SECTION, 3);
        session.clearDirty();

        session.setContentQuery(SECTION, new MenuContentQuery("sword", Map.of()));
        session.setPage(SECTION, 0);

        assertTrue(session.isDirty());
        assertEquals(0, session.getPage(SECTION));
        assertEquals("&7Page &a1&7/&a2", renderPagerLore(session, 2, 1L));
    }
}

package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the fix for reconciliation gap #4 (v1's static per-player maps that
 * grew without bound because nothing ever cleared them on quit):
 * {@link MenuSessionRegistry#close} must actually drop the session, and a
 * fresh {@link MenuSessionRegistry#open} after that must start clean.
 */
class MenuSessionRegistryTest {

    @Test
    void openReturnsTheSameSessionOnRepeatCallsForTheSamePlayer() {
        MenuSessionRegistry registry = new MenuSessionRegistry();
        UUID playerId = UUID.randomUUID();

        MenuSession first = registry.open(playerId);
        MenuSession second = registry.open(playerId);

        assertSame(first, second);
        assertEquals(1, registry.activeSessionCount());
    }

    @Test
    void closeRemovesTheSessionEntirelyNotJustLeavingItForGc() {
        MenuSessionRegistry registry = new MenuSessionRegistry();
        UUID playerId = UUID.randomUUID();
        registry.open(playerId).navigateTo("some.menu");

        registry.close(playerId);

        assertFalse(registry.get(playerId).isPresent());
        assertEquals(0, registry.activeSessionCount());
    }

    @Test
    void reopeningAfterCloseStartsWithFreshNavigationState() {
        MenuSessionRegistry registry = new MenuSessionRegistry();
        UUID playerId = UUID.randomUUID();
        MenuSession original = registry.open(playerId);
        original.navigateTo("menu.a");
        original.navigateTo("menu.b");

        registry.close(playerId);
        MenuSession reopened = registry.open(playerId);

        assertNotSame(original, reopened);
        assertTrue(reopened.currentMenuKey().isEmpty());
    }

    @Test
    void sessionNavigationStackSupportsBackNavigation() {
        MenuSession session = new MenuSessionRegistry().open(UUID.randomUUID());

        session.navigateTo("menu.a");
        session.navigateTo("menu.b");
        session.navigateTo("menu.c");

        assertEquals("menu.c", session.currentMenuKey().orElseThrow());
        assertEquals("menu.b", session.goBack().orElseThrow());
        assertEquals("menu.a", session.goBack().orElseThrow());
        assertTrue(session.goBack().isEmpty());
        // Going back past the bottom of the stack leaves the last known menu current.
        assertEquals("menu.a", session.currentMenuKey().orElseThrow());
    }

    @Test
    void sectionPagingWrapsAndDefaultsToZero() {
        MenuSession session = new MenuSessionRegistry().open(UUID.randomUUID());

        assertEquals(0, session.getPage(7));
        assertEquals(1, session.nextPage(7, 3));
        assertEquals(2, session.nextPage(7, 3));
        assertEquals(0, session.nextPage(7, 3)); // wraps past the last page back to 0
        assertEquals(2, session.previousPage(7, 3)); // wraps below 0 back to the last page
        assertEquals(1, session.previousPage(7, 3));
        assertEquals(0, session.previousPage(7, 3));
    }

    @Test
    void firstPageJumpsToZeroRegardlessOfCurrentPage() {
        MenuSession session = new MenuSessionRegistry().open(UUID.randomUUID());

        session.nextPage(7, 3);
        session.nextPage(7, 3);
        assertEquals(2, session.getPage(7));

        session.firstPage(7);
        assertEquals(0, session.getPage(7));
    }

    @Test
    void stepPageAllowsNegativeUnlikeSetPage() {
        MenuSession session = new MenuSessionRegistry().open(UUID.randomUUID());

        session.stepPage(7, -1);
        assertEquals(-1, session.getPage(7));

        session.stepPage(7, -2);
        assertEquals(-3, session.getPage(7));
    }

    @Test
    void dirtyFlagDefaultsFalseAndCanBeSetAndCleared() {
        MenuSession session = new MenuSessionRegistry().open(UUID.randomUUID());

        assertFalse(session.isDirty());
        session.markDirty();
        assertTrue(session.isDirty());
        session.clearDirty();
        assertFalse(session.isDirty());
    }
}

package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.menu.MenuTransitionTracker.CloseVerdict;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Alpha cross-play finding 2026-10-24: delayed menu switches for Bedrock players. */
class MenuTransitionTrackerTest {

    private final MenuTransitionTracker tracker = new MenuTransitionTracker();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void aCloseWithoutTransitionIsNormal() {
        assertEquals(CloseVerdict.NORMAL, tracker.onClientClose(alice, 100));
        assertFalse(tracker.isPending(alice));
    }

    @Test
    void aCloseBeforeTheDelayedOpenIsTheStaleCloseOfThePreviousMenu() {
        long token = tracker.begin(alice);

        assertTrue(tracker.isPending(alice));
        assertEquals(CloseVerdict.STALE_IGNORE, tracker.onClientClose(alice, 101));
        assertEquals(CloseVerdict.STALE_IGNORE, tracker.onClientClose(alice, 102), "still pending: every close is stale");
        assertTrue(tracker.opened(alice, token, 103, 3), "the stale close does not cancel the open");
        assertFalse(tracker.isPending(alice));
    }

    @Test
    void aCloseRightAfterTheOpenReopensOnceThenClosesAreReal() {
        long token = tracker.begin(alice);
        assertTrue(tracker.opened(alice, token, 103, 3));

        assertEquals(CloseVerdict.STALE_REOPEN, tracker.onClientClose(alice, 105));
        assertEquals(CloseVerdict.NORMAL, tracker.onClientClose(alice, 106), "the player closing the reopened menu");
    }

    @Test
    void aCloseAfterTheGraceWindowIsNormal() {
        long token = tracker.begin(alice);
        tracker.opened(alice, token, 103, 3);

        assertEquals(CloseVerdict.NORMAL, tracker.onClientClose(alice, 107));
        assertEquals(CloseVerdict.NORMAL, tracker.onClientClose(alice, 108));
    }

    @Test
    void theGraceWindowIncludesItsLastTick() {
        long token = tracker.begin(alice);
        tracker.opened(alice, token, 100, 3);

        assertEquals(CloseVerdict.STALE_REOPEN, tracker.onClientClose(alice, 103));
    }

    @Test
    void noGraceEndsTheTransitionAtTheOpen() {
        long token = tracker.begin(alice);

        assertTrue(tracker.opened(alice, token, 103, 0));
        assertEquals(CloseVerdict.NORMAL, tracker.onClientClose(alice, 103));
    }

    @Test
    void aNewerTransitionSupersedesTheOlderOpen() {
        long first = tracker.begin(alice);
        long second = tracker.begin(alice);

        assertFalse(tracker.opened(alice, first, 103, 3), "the double click's first open is dropped");
        assertTrue(tracker.isPending(alice));
        assertTrue(tracker.opened(alice, second, 104, 3));
    }

    @Test
    void aTokenOpensOnlyOnce() {
        long token = tracker.begin(alice);

        assertTrue(tracker.opened(alice, token, 103, 3));
        assertFalse(tracker.opened(alice, token, 104, 3));
    }

    @Test
    void cancelDropsThePendingOpen() {
        long token = tracker.begin(alice);
        tracker.cancel(alice);

        assertFalse(tracker.isPending(alice));
        assertFalse(tracker.opened(alice, token, 103, 3));
        assertEquals(CloseVerdict.NORMAL, tracker.onClientClose(alice, 103));
    }

    @Test
    void playersAreTrackedIndependently() {
        long aliceToken = tracker.begin(alice);

        assertEquals(CloseVerdict.NORMAL, tracker.onClientClose(bob, 101));
        assertFalse(tracker.opened(bob, aliceToken, 101, 3), "another player's token opens nothing");
        assertTrue(tracker.isPending(alice));
    }
}

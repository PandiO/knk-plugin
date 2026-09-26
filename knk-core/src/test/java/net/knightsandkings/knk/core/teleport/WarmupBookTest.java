package net.knightsandkings.knk.core.teleport;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarmupBookTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("Alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("Bob".getBytes());

    @Test
    void warmupIsNotDueBeforeItsSecondsHavePassed() {
        WarmupBook<String> book = new WarmupBook<>();
        book.start(ALICE, "spawn", 1_000, 5);

        assertTrue(book.takeDue(5_999).isEmpty());
        assertTrue(book.isWarmingUp(ALICE));
        assertEquals(1, book.get(ALICE).orElseThrow().secondsLeft(5_001));
        assertEquals(5, book.get(ALICE).orElseThrow().secondsLeft(1_000));
    }

    @Test
    void dueWarmupIsTakenExactlyOnce() {
        WarmupBook<String> book = new WarmupBook<>();
        book.start(ALICE, "spawn", 1_000, 5);

        List<WarmupBook.Pending<String>> due = book.takeDue(6_000);

        assertEquals(1, due.size());
        assertEquals("spawn", due.get(0).payload());
        assertFalse(book.isWarmingUp(ALICE));
        assertTrue(book.takeDue(7_000).isEmpty(), "a second tick must not commit it again");
    }

    @Test
    void startingANewWarmupReplacesAndReturnsTheOldOne() {
        WarmupBook<String> book = new WarmupBook<>();
        book.start(ALICE, "spawn", 0, 5);

        var replaced = book.start(ALICE, "warp", 2_000, 5);

        assertEquals("spawn", replaced.orElseThrow().payload());
        assertEquals(1, book.size());
        assertTrue(book.takeDue(5_000).isEmpty(), "the new warmup restarted the clock");
        assertEquals("warp", book.takeDue(7_000).get(0).payload());
    }

    @Test
    void cancelledWarmupNeverBecomesDue() {
        WarmupBook<String> book = new WarmupBook<>();
        book.start(ALICE, "spawn", 0, 5);

        assertEquals("spawn", book.cancel(ALICE).orElseThrow().payload());
        assertTrue(book.cancel(ALICE).isEmpty(), "cancelling twice is a no-op");
        assertTrue(book.takeDue(10_000).isEmpty());
    }

    @Test
    void warmupsOfDifferentPlayersAreIndependent() {
        WarmupBook<String> book = new WarmupBook<>();
        book.start(ALICE, "a", 0, 3);
        book.start(BOB, "b", 0, 5);

        List<WarmupBook.Pending<String>> due = book.takeDue(3_000);

        assertEquals(1, due.size());
        assertEquals(ALICE, due.get(0).subject());
        assertTrue(book.isWarmingUp(BOB));
    }

    @Test
    void zeroSecondWarmupIsDueImmediately() {
        WarmupBook<String> book = new WarmupBook<>();
        book.start(ALICE, "now", 500, 0);

        assertEquals(1, book.takeDue(500).size());
    }

    @Test
    void onlyABlockChangeCountsAsMovement() {
        assertFalse(WarmupBook.changesBlock(10, 64, 10, 10, 64, 10), "head rotation / sub-block move");
        assertTrue(WarmupBook.changesBlock(10, 64, 10, 11, 64, 10));
        assertTrue(WarmupBook.changesBlock(10, 64, 10, 10, 65, 10));
        assertTrue(WarmupBook.changesBlock(10, 64, 10, 10, 64, 9));
    }
}

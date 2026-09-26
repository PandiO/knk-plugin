package net.knightsandkings.knk.core.teleport;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatTagBookTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("Alice".getBytes());

    @Test
    void untaggedPlayerIsNotInCombat() {
        CombatTagBook book = new CombatTagBook(10);

        assertFalse(book.isTagged(ALICE, 0));
        assertEquals(0, book.remainingSeconds(ALICE, 0));
        assertEquals(-1, book.secondsSinceCombat(ALICE, 0));
    }

    @Test
    void tagLastsTheConfiguredSeconds() {
        CombatTagBook book = new CombatTagBook(10);
        book.tag(ALICE, 1_000);

        assertTrue(book.isTagged(ALICE, 10_999));
        assertEquals(1, book.remainingSeconds(ALICE, 10_500));
        assertEquals(9, book.secondsSinceCombat(ALICE, 10_500));
        assertFalse(book.isTagged(ALICE, 11_000));
    }

    @Test
    void aNewHitRestartsTheTag() {
        CombatTagBook book = new CombatTagBook(10);
        book.tag(ALICE, 0);
        book.tag(ALICE, 8_000);

        assertTrue(book.isTagged(ALICE, 15_000));
        assertEquals(3, book.remainingSeconds(ALICE, 15_000));
    }

    @Test
    void zeroSecondTagDisablesTheCheck() {
        CombatTagBook book = new CombatTagBook(0);
        book.tag(ALICE, 0);

        assertFalse(book.isTagged(ALICE, 0));
    }

    @Test
    void purgeDropsOnlyExpiredTags() {
        CombatTagBook book = new CombatTagBook(10);
        UUID bob = UUID.nameUUIDFromBytes("Bob".getBytes());
        book.tag(ALICE, 0);
        book.tag(bob, 5_000);

        book.purgeExpired(12_000);

        assertEquals(-1, book.secondsSinceCombat(ALICE, 12_000));
        assertTrue(book.isTagged(bob, 12_000));
    }
}

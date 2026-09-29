package net.knightsandkings.knk.core.teleport;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /back's death book (docs/specs/teleport/DESIGN.md Phase 7): 5 minutes, single use per death. */
class BackLocationBookTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("Alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("Bob".getBytes());

    private final BackLocationBook<String> book = new BackLocationBook<>(300);

    @Test
    void deathIsAvailableForFiveMinutes() {
        book.recordDeath(ALICE, "lava lake", 0);

        assertEquals("lava lake", book.available(ALICE, 0).orElseThrow().location());
        assertEquals(300, book.available(ALICE, 0).orElseThrow().secondsLeft(0));
        assertTrue(book.available(ALICE, 299_999).isPresent());
        assertTrue(book.available(ALICE, 300_000).isEmpty());
        assertTrue(book.claim(ALICE, 300_000).isEmpty());
        assertTrue(book.available(BOB, 0).isEmpty());
    }

    @Test
    void aNewerDeathReplacesTheOlderOne() {
        book.recordDeath(ALICE, "first", 0);
        book.recordDeath(ALICE, "second", 200_000);

        BackLocationBook.Entry<String> entry = book.available(ALICE, 450_000).orElseThrow();
        assertEquals("second", entry.location());
    }

    @Test
    void aClaimedDeathCantBeClaimedTwice_AndIsUsedUpOnArrival() {
        book.recordDeath(ALICE, "cave", 0);

        BackLocationBook.Entry<String> claimed = book.claim(ALICE, 1_000).orElseThrow();
        assertTrue(book.isClaimed(ALICE));
        assertTrue(book.claim(ALICE, 1_000).isEmpty());
        assertTrue(book.available(ALICE, 1_000).isEmpty());

        book.consume(claimed);
        assertFalse(book.isClaimed(ALICE));
        assertTrue(book.claim(ALICE, 2_000).isEmpty());
        assertEquals(0, book.size());
    }

    @Test
    void aReleasedClaimCanBeUsedAgainUntilItExpires() {
        book.recordDeath(ALICE, "cave", 0);

        book.release(book.claim(ALICE, 1_000).orElseThrow());

        assertTrue(book.claim(ALICE, 2_000).isPresent());
    }

    @Test
    void aStaleClaimDoesntTouchANewerDeath() {
        book.recordDeath(ALICE, "first", 0);
        BackLocationBook.Entry<String> first = book.claim(ALICE, 1_000).orElseThrow();
        book.recordDeath(ALICE, "second", 2_000);

        book.consume(first);
        book.release(first);

        Optional<BackLocationBook.Entry<String>> second = book.available(ALICE, 3_000);
        assertEquals("second", second.orElseThrow().location());
    }

    @Test
    void aBackStartedInTimeSurvivesTheDeadline_ButPurgeDropsExpiredUnclaimedDeaths() {
        book.recordDeath(ALICE, "cave", 0);
        book.recordDeath(BOB, "void", 0);
        BackLocationBook.Entry<String> claimed = book.claim(ALICE, 298_000).orElseThrow();

        book.purgeExpired(301_000);

        assertEquals(1, book.size());
        assertTrue(book.isClaimed(ALICE));
        book.release(claimed);
        book.purgeExpired(301_000);
        assertEquals(0, book.size());
    }

    @Test
    void expiryIsAtLeastOneSecond_AndAReloadAppliesToNewDeaths() {
        BackLocationBook<String> misconfigured = new BackLocationBook<>(0);
        misconfigured.recordDeath(ALICE, "x", 0);
        assertTrue(misconfigured.available(ALICE, 999).isPresent());

        book.recordDeath(ALICE, "old", 0);
        book.setExpireSeconds(10);
        assertTrue(book.available(ALICE, 20_000).isPresent());
        book.recordDeath(BOB, "new", 0);
        assertTrue(book.available(BOB, 10_000).isEmpty());
    }

    @Test
    void clearForgetsTheDeath() {
        book.recordDeath(ALICE, "cave", 0);
        book.clear(ALICE);

        assertTrue(book.available(ALICE, 0).isEmpty());
    }

    @Test
    void backSettingsDefaultToFiveMinutesAndClampTheExpiry() {
        assertEquals(new TeleportBackSettings(true, 300), TeleportBackSettings.defaults());
        assertEquals(1, new TeleportBackSettings(true, -4).expireSeconds());
        assertEquals(TeleportBackSettings.defaults(), TeleportSettings.defaults().back());
        assertEquals(TeleportBackSettings.defaults(), new TeleportSettings(5, 3, 30, 10, 3).back());
    }
}

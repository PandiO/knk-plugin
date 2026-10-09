package net.knightsandkings.knk.core.teleport;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /back's book (docs/specs/teleport/DESIGN.md Phase 7, KNG-42): one entry per kind, the latest
 * among the allowed kinds wins, each single use and only for its window.
 */
class BackLocationBookTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("Alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("Bob".getBytes());
    private static final Set<BackKind> DEATH = Set.of(BackKind.DEATH);
    private static final Set<BackKind> ALL = EnumSet.allOf(BackKind.class);

    private final BackLocationBook<String> book = new BackLocationBook<>();

    private BackLocationBook.Entry<String> died(UUID player, String where, long at) {
        return book.record(player, BackKind.DEATH, where, at, 300);
    }

    @Test
    void anEntryIsAvailableForItsWindow() {
        died(ALICE, "lava lake", 0);

        assertEquals("lava lake", book.available(ALICE, DEATH, 0).orElseThrow().location());
        assertEquals(300, book.available(ALICE, DEATH, 0).orElseThrow().secondsLeft(0));
        assertTrue(book.available(ALICE, DEATH, 299_999).isPresent());
        assertTrue(book.available(ALICE, DEATH, 300_000).isEmpty());
        assertTrue(book.claim(ALICE, DEATH, 300_000).isEmpty());
        assertTrue(book.available(BOB, DEATH, 0).isEmpty());
    }

    @Test
    void aNewerEntryOfTheSameKindReplacesTheOlderOne() {
        died(ALICE, "first", 0);
        died(ALICE, "second", 200_000);

        assertEquals("second", book.available(ALICE, DEATH, 450_000).orElseThrow().location());
        assertEquals(1, book.size());
    }

    @Test
    void theLatestEntryAmongTheAllowedKindsWins() {
        book.record(ALICE, BackKind.WARPS, "before warp", 0, 300);
        died(ALICE, "cave", 1_000);
        book.record(ALICE, BackKind.SPAWN, "before spawn", 2_000, 300);

        assertEquals("before spawn", book.available(ALICE, ALL, 3_000).orElseThrow().location());
        assertEquals("cave", book.available(ALICE, Set.of(BackKind.DEATH, BackKind.WARPS), 3_000).orElseThrow().location());
        assertEquals("before warp", book.available(ALICE, Set.of(BackKind.WARPS), 3_000).orElseThrow().location());
        assertTrue(book.available(ALICE, Set.of(BackKind.TELEPORT), 3_000).isEmpty());
        assertTrue(book.available(ALICE, Set.of(), 3_000).isEmpty());
    }

    @Test
    void anExpiredLaterEntryLetsAnOlderOneThrough() {
        book.record(ALICE, BackKind.WARPS, "before warp", 0, 600);
        book.record(ALICE, BackKind.SPAWN, "before spawn", 1_000, 60);

        assertEquals("before spawn", book.available(ALICE, ALL, 60_000).orElseThrow().location());
        assertEquals("before warp", book.available(ALICE, ALL, 61_000).orElseThrow().location());
    }

    @Test
    void usingTheLatestLeavesTheNextOneForASecondBack() {
        book.record(ALICE, BackKind.WARPS, "before warp", 0, 300);
        died(ALICE, "cave", 1_000);

        BackLocationBook.Entry<String> first = book.claim(ALICE, ALL, 2_000).orElseThrow();
        assertEquals("cave", first.location());
        book.consume(first);

        assertEquals("before warp", book.claim(ALICE, ALL, 3_000).orElseThrow().location());
    }

    @Test
    void aClaimedEntryCantBeClaimedTwice_AndIsUsedUpOnArrival() {
        died(ALICE, "cave", 0);

        BackLocationBook.Entry<String> claimed = book.claim(ALICE, DEATH, 1_000).orElseThrow();
        assertTrue(book.isClaimed(ALICE));
        assertTrue(book.claim(ALICE, DEATH, 1_000).isEmpty());
        assertTrue(book.available(ALICE, DEATH, 1_000).isEmpty());

        book.consume(claimed);
        assertFalse(book.isClaimed(ALICE));
        assertTrue(book.claim(ALICE, DEATH, 2_000).isEmpty());
        assertEquals(0, book.size());
    }

    @Test
    void oneBackPerPlayerAtATime_EvenWithAnotherKindLeft() {
        book.record(ALICE, BackKind.WARPS, "before warp", 0, 300);
        died(ALICE, "cave", 1_000);

        book.claim(ALICE, ALL, 2_000).orElseThrow();

        assertTrue(book.claim(ALICE, ALL, 2_000).isEmpty());
        assertEquals("before warp", book.available(ALICE, ALL, 2_000).orElseThrow().location());
    }

    @Test
    void aReleasedClaimCanBeUsedAgainUntilItExpires() {
        died(ALICE, "cave", 0);

        book.release(book.claim(ALICE, DEATH, 1_000).orElseThrow());

        assertTrue(book.claim(ALICE, DEATH, 2_000).isPresent());
    }

    @Test
    void aStaleClaimDoesntTouchANewerEntry() {
        died(ALICE, "first", 0);
        BackLocationBook.Entry<String> first = book.claim(ALICE, DEATH, 1_000).orElseThrow();
        died(ALICE, "second", 2_000);

        book.consume(first);
        book.release(first);

        Optional<BackLocationBook.Entry<String>> second = book.available(ALICE, DEATH, 3_000);
        assertEquals("second", second.orElseThrow().location());
        assertFalse(book.isClaimed(ALICE));
    }

    @Test
    void aBackStartedInTimeSurvivesTheDeadline_ButPurgeDropsExpiredUnclaimedEntries() {
        died(ALICE, "cave", 0);
        died(BOB, "void", 0);
        book.record(BOB, BackKind.WARPS, "before warp", 0, 600);
        BackLocationBook.Entry<String> claimed = book.claim(ALICE, DEATH, 298_000).orElseThrow();

        book.purgeExpired(301_000);

        assertEquals(2, book.size(), "Alice's claimed death and Bob's warp stay");
        assertTrue(book.isClaimed(ALICE));
        book.release(claimed);
        book.purgeExpired(301_000);
        assertEquals(1, book.size());
        book.purgeExpired(600_000);
        assertEquals(0, book.size());
    }

    @Test
    void expiryIsAtLeastOneSecond() {
        book.record(ALICE, BackKind.DEATH, "x", 0, 0);

        assertTrue(book.available(ALICE, DEATH, 999).isPresent());
        assertTrue(book.available(ALICE, DEATH, 1_000).isEmpty());
    }

    @Test
    void clearForgetsEveryEntry() {
        died(ALICE, "cave", 0);
        book.record(ALICE, BackKind.SPAWN, "before spawn", 0, 300);
        book.clear(ALICE);

        assertTrue(book.available(ALICE, ALL, 0).isEmpty());
    }

    @Test
    void backSettingsDefaultToFiveMinutesFree_AndClampTheirValues() {
        assertEquals(new TeleportBackSettings(true, 300), TeleportBackSettings.defaults());
        assertEquals(new TeleportBackSettings(true, 300, Map.of(), 0), TeleportBackSettings.defaults());
        assertFalse(TeleportBackSettings.defaults().isPaid());
        assertEquals(1, new TeleportBackSettings(true, -4).expireSeconds());
        assertEquals(TeleportBackSettings.defaults(), TeleportSettings.defaults().back());
        assertEquals(TeleportBackSettings.defaults(), new TeleportSettings(5, 3, 30, 10, 3).back());

        TeleportBackSettings custom = new TeleportBackSettings(true, 300,
            Map.of(BackKind.WARPS, 600, BackKind.SPAWN, -5), -10);
        assertEquals(600, custom.expireSeconds(BackKind.WARPS));
        assertEquals(1, custom.expireSeconds(BackKind.SPAWN));
        assertEquals(300, custom.expireSeconds(BackKind.DEATH));
        assertEquals(0, custom.priceCoins());
        assertTrue(new TeleportBackSettings(true, 300, null, 50).isPaid());
    }

    @Test
    void kindsAreFoundByTheirConfigKey() {
        assertEquals(Optional.of(BackKind.WARPS), BackKind.fromConfigKey(" Warps "));
        assertEquals(Optional.of(BackKind.DEATH), BackKind.fromConfigKey("death"));
        assertTrue(BackKind.fromConfigKey("warp").isEmpty());
        assertTrue(BackKind.fromConfigKey(null).isEmpty());
    }
}

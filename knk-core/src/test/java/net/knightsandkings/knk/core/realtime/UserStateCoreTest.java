package net.knightsandkings.knk.core.realtime;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.offline.OfflineSecurityStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PandiO/knk-workspace#179 (S2): the slower fallback re-syncs while connected (DESIGN §7.2, Q4), dropping a user's
 * offline permission answers (#76) and the balances an event carries.
 */
class UserStateCoreTest {

    @Test
    void throttleRunsEveryTimeWhileDisconnected_AndEveryNthTimeWhileConnected() {
        AtomicBoolean connected = new AtomicBoolean(false);
        FallbackPolling polling = new FallbackPolling(connected::get, () -> 4);
        AtomicInteger runs = new AtomicInteger();
        Runnable timer = polling.throttle(runs::incrementAndGet);

        for (int i = 0; i < 3; i++) {
            timer.run();
        }
        assertEquals(3, runs.get());

        connected.set(true);
        for (int i = 0; i < 8; i++) {
            timer.run();
        }
        assertEquals(5, runs.get()); // runs 4 and 8 while connected

        connected.set(false);
        timer.run();
        assertEquals(6, runs.get());
    }

    @Test
    void intervalIsMultipliedOnlyWhileConnected_AndAMultiplierBelowOneMeansNone() {
        AtomicBoolean connected = new AtomicBoolean(true);
        assertEquals(Duration.ofMinutes(4), new FallbackPolling(connected::get, () -> 4).interval(Duration.ofMinutes(1)));
        assertEquals(Duration.ofMinutes(1), new FallbackPolling(connected::get, () -> 0).interval(Duration.ofMinutes(1)));
        connected.set(false);
        assertEquals(Duration.ofMinutes(1), new FallbackPolling(connected::get, () -> 4).interval(Duration.ofMinutes(1)));
        assertFalse(FallbackPolling.never().connected());
        assertEquals(1, FallbackPolling.never().multiplier());
    }

    @Test
    void forgetPermissionAnswersDropsOnlyThatUsersAnswers() {
        OfflineSecurityStore store = new OfflineSecurityStore(null, OfflineSecurityStore.Settings.defaults(), Clock.systemUTC());
        store.recordPermission(7, "knk.mode.staff", true);
        store.recordPermission(7, "knk.socialspy", true);
        store.recordPermission(8, "knk.mode.staff", true);

        store.forgetPermissionAnswers(7);

        assertEquals(Optional.empty(), store.permission(7, "knk.mode.staff"));
        assertEquals(Optional.empty(), store.permission(7, "knk.socialspy"));
        assertEquals(Optional.of(true), store.permission(8, "knk.mode.staff"));
        assertEquals(1, store.permissionCount());
    }

    @Test
    void updateBalancesWithExperienceReturnsTheEntryBefore_AndIgnoresUncachedPlayers() {
        UserCache cache = new UserCache(Duration.ofMinutes(1));
        UUID uuid = UUID.randomUUID();
        assertNull(cache.updateBalances(uuid, 1, 2, 3));

        cache.put(new UserSummary(7, "Alice", uuid, null, 100, 5, 40, true));
        UserSummary before = cache.updateBalances(uuid, 150, 6, 90);

        assertEquals(40, before.experiencePoints());
        UserSummary after = cache.getStale(uuid).orElseThrow();
        assertEquals(150, after.coins());
        assertEquals(6, after.gems());
        assertEquals(90, after.experiencePoints());
        assertNull(cache.updateBalances(uuid, -1, 0, 0));
        assertTrue(cache.getStale(uuid).isPresent());
    }
}

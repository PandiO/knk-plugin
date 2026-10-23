package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.compass.CompassItemRule;
import net.knightsandkings.knk.core.navigation.compass.CompassItemRule.NameMatch;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import net.knightsandkings.knk.core.ports.api.NavigationCompassSettingsApi;

/** The compass settings come from memory, refresh in the background and survive an API outage (KNG-234). */
class CompassSettingsStoreTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final AtomicInteger calls = new AtomicInteger();
    private CompletableFuture<CompassSettings> answer = CompletableFuture.completedFuture(CompassSettings.defaults());
    private final NavigationCompassSettingsApi api = () -> {
        calls.incrementAndGet();
        return answer;
    };
    private final CompassSettingsStore store = new CompassSettingsStore(api, now::get);

    private static CompassSettings recovery() {
        return new CompassSettings(new CompassItemRule(true, "RECOVERY_COMPASS", "", NameMatch.EXACT), true, true, List.of("town:A"));
    }

    @Test
    void theDefaultsApplyUntilTheFirstAnswerAndTheFirstCallStartsTheRead() {
        answer = new CompletableFuture<>();

        CompassSettings first = store.get();

        assertEquals(CompassSettings.defaults(), first);
        assertEquals(1, calls.get());
    }

    @Test
    void anAnswerReplacesTheSettings() {
        answer = CompletableFuture.completedFuture(recovery());

        store.get();

        assertTrue(store.get().item().matches("RECOVERY_COMPASS", null));
        assertFalse(store.get().item().matches("COMPASS", "Navigation Compass"));
    }

    @Test
    void fresh_settings_are_not_read_again_but_stale_ones_are() {
        store.get();
        store.get();
        assertEquals(1, calls.get());

        now.addAndGet(CompassSettingsStore.REFRESH_MILLIS);
        store.get();
        assertEquals(2, calls.get());
    }

    @Test
    void aFailedReadKeepsTheLastGoodSettingsAndWaitsForTheInterval() {
        answer = CompletableFuture.completedFuture(recovery());
        store.get();
        CompassSettings good = store.get();

        now.addAndGet(CompassSettingsStore.REFRESH_MILLIS);
        answer = CompletableFuture.failedFuture(new RuntimeException("api down"));
        assertSame(good, store.get());
        assertSame(good, store.get());
        assertEquals(2, calls.get());
    }

    @Test
    void aReadInFlightIsNotStartedTwice() {
        answer = new CompletableFuture<>();
        store.refresh();
        store.refresh();
        assertEquals(1, calls.get());
    }
}

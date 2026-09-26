package net.knightsandkings.knk.core.dataaccess;

import static net.knightsandkings.knk.core.teleport.TeleportTestDestinations.open;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.ports.api.TeleportDestinationsQueryApi;

/** Per-player warp list cache (docs/specs/teleport/DESIGN.md §3.7.4, Phase 5). */
class TeleportDestinationsDataAccessTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final List<Integer> calls = new ArrayList<>();
    private final TeleportDestinationsQueryApi api = userId -> {
        calls.add(userId);
        return CompletableFuture.completedFuture(List.of(open(userId * 10, "Place" + userId, "Town")));
    };
    private final MutableClock clock = new MutableClock();
    private final TeleportDestinationsDataAccess access = new TeleportDestinationsDataAccess(api, Duration.ofSeconds(60), clock);

    @Test
    void eachPlayerHasTheirOwnListCachedForTheTtl() {
        List<KnkTeleportDestination> seven = access.listAsync(7).join();
        access.listAsync(7).join();
        access.listAsync(8).join();

        assertEquals("Place7", seven.get(0).name());
        assertEquals(List.of(7, 8), calls);
        assertTrue(access.listAsync(7).isDone(), "fresh lists answer at once (main-thread safe)");

        clock.now = clock.now.plusSeconds(61);
        access.listAsync(7).join();
        assertEquals(List.of(7, 8, 7), calls);
    }

    @Test
    void invalidateRefetchesOnePlayer_InvalidateAllEveryone() {
        access.listAsync(7).join();
        access.listAsync(8).join();

        access.invalidate(7);
        access.listAsync(7).join();
        access.listAsync(8).join();
        assertEquals(List.of(7, 8, 7), calls);

        access.invalidateAll();
        assertTrue(access.cachedOrEmpty(8).isEmpty());
        access.listAsync(8).join();
        assertEquals(List.of(7, 8, 7, 8), calls);
    }

    @Test
    void cachedOrEmptyNeverFetches() {
        assertTrue(access.cachedOrEmpty(7).isEmpty());
        assertTrue(calls.isEmpty());
        access.listAsync(7).join();
        assertEquals(1, access.cachedOrEmpty(7).size());
        access.forget(7);
        assertTrue(access.cachedOrEmpty(7).isEmpty());
    }
}

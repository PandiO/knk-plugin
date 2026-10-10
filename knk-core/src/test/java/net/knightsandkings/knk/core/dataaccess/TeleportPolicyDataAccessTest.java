package net.knightsandkings.knk.core.dataaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.domain.teleport.KnkTeleportPolicy;
import net.knightsandkings.knk.core.ports.api.TeleportDestinationsQueryApi;

/** The per-player cache of permission-group teleport settings (Linear KNG-41). */
class TeleportPolicyDataAccessTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID NOBODY = UUID.randomUUID();
    private static final KnkTeleportPolicy CHEAP = new KnkTeleportPolicy(
        new KnkTeleportPolicy.Kind("Fixed", null, 1, null, null, 3), null, null);

    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean down;
    private final MutableClock clock = new MutableClock();

    private final TeleportDestinationsQueryApi api = new TeleportDestinationsQueryApi() {
        @Override
        public CompletableFuture<List<KnkTeleportDestination>> listForUser(int userId) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<KnkTeleportPolicy> policyForUser(int userId) {
            calls.incrementAndGet();
            return down ? CompletableFuture.failedFuture(new RuntimeException("down")) : CompletableFuture.completedFuture(CHEAP);
        }
    };

    private final TeleportPolicyDataAccess policies = new TeleportPolicyDataAccess(api,
        uuid -> CompletableFuture.completedFuture(ALICE.equals(uuid) ? 7 : null), Duration.ofSeconds(60), clock);

    @Test
    void fetchedOnce_ThenServedFromTheCacheUntilTooOld() {
        assertSame(KnkTeleportPolicy.DEFAULT, policies.cachedOrDefault(ALICE));
        assertSame(CHEAP, policies.getAsync(ALICE, Duration.ofSeconds(5)).join());
        assertSame(CHEAP, policies.getAsync(ALICE, Duration.ofSeconds(5)).join());
        assertEquals(1, calls.get());
        assertSame(CHEAP, policies.cachedOrDefault(ALICE));

        clock.advance(Duration.ofSeconds(6));
        policies.getAsync(ALICE, Duration.ofSeconds(5)).join();
        assertEquals(2, calls.get());
    }

    @Test
    void aFailedRefreshKeepsTheStaleCopy_NoCopyAtAllMeansDefaults() {
        policies.getAsync(ALICE, Duration.ofSeconds(5)).join();
        down = true;
        clock.advance(Duration.ofSeconds(10));
        assertSame(CHEAP, policies.getAsync(ALICE, Duration.ofSeconds(5)).join());

        policies.invalidateAll();
        assertSame(KnkTeleportPolicy.DEFAULT, policies.getAsync(ALICE, Duration.ofSeconds(5)).join());
    }

    @Test
    void aPlayerWithoutAnAccountGetsTheDefaults() {
        assertSame(KnkTeleportPolicy.DEFAULT, policies.getAsync(NOBODY, Duration.ofSeconds(5)).join());
        assertEquals(0, calls.get());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.ofEpochSecond(1_000);

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

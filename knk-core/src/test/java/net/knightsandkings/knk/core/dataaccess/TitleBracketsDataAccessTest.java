package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.domain.users.TitleBracket;
import net.knightsandkings.knk.core.ports.api.TitleBracketsQueryApi;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** InventoryMenu content port CP3: the cached title-bracket list. */
class TitleBracketsDataAccessTest {

    private static final TitleBracket SQUIRE = new TitleBracket(2, "Squire", "Maid", 100, 20, 0, 0, 0);
    private static final TitleBracket PEASANT = new TitleBracket(1, "Peasant", "Peasant", 0, 10, 0, 0, 0);

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-25T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void sortsByMinExperienceAndServesTheCacheUntilTheTtlRunsOut() {
        AtomicInteger calls = new AtomicInteger();
        TitleBracketsQueryApi api = () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(List.of(SQUIRE, PEASANT));
        };
        MutableClock clock = new MutableClock();
        TitleBracketsDataAccess access = new TitleBracketsDataAccess(api, Duration.ofMinutes(10), clock);

        assertTrue(access.cachedOrEmpty().isEmpty());
        assertEquals(List.of(PEASANT, SQUIRE), access.listAsync().join());
        CompletableFuture<List<TitleBracket>> second = access.listAsync();
        assertTrue(second.isDone(), "a fresh cache answers with a completed future (main-thread safe)");
        assertEquals(1, calls.get());

        clock.now = clock.now.plus(Duration.ofMinutes(11));
        access.listAsync().join();
        assertEquals(2, calls.get());
        assertEquals(List.of(PEASANT, SQUIRE), access.cachedOrEmpty());
    }

    @Test
    void concurrentMissesShareOneRequestAndAFailureServesTheStaleList() {
        CompletableFuture<List<TitleBracket>> pending = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        TitleBracketsQueryApi api = () -> {
            calls.incrementAndGet();
            return calls.get() == 1 ? pending : CompletableFuture.failedFuture(new RuntimeException("down"));
        };
        MutableClock clock = new MutableClock();
        TitleBracketsDataAccess access = new TitleBracketsDataAccess(api, Duration.ofMinutes(10), clock);

        CompletableFuture<List<TitleBracket>> a = access.listAsync();
        CompletableFuture<List<TitleBracket>> b = access.listAsync();
        assertSame(a, b);
        assertFalse(a.isDone());
        pending.complete(List.of(PEASANT));
        assertEquals(1, calls.get());

        access.invalidate();
        assertEquals(List.of(PEASANT), access.listAsync().join(), "failed refresh -> stale list");
    }

    @Test
    void nameForPicksTheFemaleNameOnlyForFemale() {
        assertEquals("Maid", SQUIRE.nameFor("Female"));
        assertEquals("Maid", SQUIRE.nameFor("female"));
        assertEquals("Squire", SQUIRE.nameFor("Male"));
        assertEquals("Squire", SQUIRE.nameFor(null));
    }
}

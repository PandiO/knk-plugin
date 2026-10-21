package net.knightsandkings.knk.core.dataaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.arenas.ArenaFixtures;
import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaBattleground;
import net.knightsandkings.knk.core.ports.api.ArenasApi;

/** Arena duels KNG-210: the arena gateway is cache-first, keeps the last copy when a refresh fails, and looks up offline. */
class ArenasDataAccessTest {

    private static final DataAccessSettings NO_RETRY =
            new DataAccessSettings(FetchPolicy.CACHE_FIRST, true, RetryPolicy.noRetry());

    private final FakeArenasApi api = new FakeArenasApi();

    @Test
    void readsCacheFirst() {
        ArenasDataAccess access = new ArenasDataAccess(Duration.ofMinutes(15), api, NO_RETRY);

        FetchResult<List<KnkArena>> first = access.getAllAsync().join();
        FetchResult<List<KnkArena>> second = access.getAllAsync().join();

        assertTrue(first.isFromApi());
        assertTrue(second.isFromCache());
        assertSame(first.orElseThrow(), second.orElseThrow());
        assertEquals(1, api.calls.get());
    }

    @Test
    void refreshAlwaysAsksTheApi() {
        ArenasDataAccess access = new ArenasDataAccess(Duration.ofMinutes(15), api, NO_RETRY);
        access.getAllAsync().join();

        FetchResult<List<KnkArena>> refreshed = access.refreshAsync().join();

        assertTrue(refreshed.isFromApi());
        assertEquals(2, api.calls.get());
        assertEquals(refreshed.orElseThrow(), access.cachedArenas().orElseThrow());
    }

    @Test
    void failedRefreshKeepsThePreviousSettings() {
        ArenasDataAccess access = new ArenasDataAccess(Duration.ofMinutes(15), api, NO_RETRY);
        List<KnkArena> previous = access.getAllAsync().join().orElseThrow();
        api.failing = true;

        FetchResult<List<KnkArena>> refreshed = access.refreshAsync().join();

        assertTrue(refreshed.isSuccess());
        assertSame(previous, refreshed.orElseThrow());
    }

    @Test
    void failedRefreshServesAnExpiredCopyAsStale() throws InterruptedException {
        ArenasDataAccess access = new ArenasDataAccess(Duration.ofMillis(1), api, NO_RETRY);
        List<KnkArena> previous = access.getAllAsync().join().orElseThrow();
        Thread.sleep(10);
        api.failing = true;

        FetchResult<List<KnkArena>> refreshed = access.refreshAsync().join();

        assertTrue(refreshed.isStale());
        assertSame(previous, refreshed.orElseThrow());
    }

    @Test
    void failedFirstFetchIsAnErrorAndCachesNothing() {
        api.failing = true;
        ArenasDataAccess access = new ArenasDataAccess(Duration.ofMinutes(15), api, NO_RETRY);

        FetchResult<List<KnkArena>> result = access.refreshAsync().join();

        assertFalse(result.isSuccess());
        assertTrue(access.cachedArenas().isEmpty());
        assertTrue(access.cachedByKey("arena_2").isEmpty());
    }

    @Test
    void lookupsByKeyIdAndBattlegroundRegionUseTheCache() {
        ArenasDataAccess access = new ArenasDataAccess(Duration.ofMinutes(15), api, NO_RETRY);
        assertTrue(access.getByKeyAsync("ARENA_2").join().isPresent());
        int calls = api.calls.get();

        assertEquals(7, access.cachedByKey("Arena_2").orElseThrow().id());
        assertEquals("arena_2", access.cachedById(7).orElseThrow().key());
        assertEquals(70, access.cachedBattlegroundByRegion("world", "ARENA_2,1-battleground").orElseThrow().id());
        assertEquals(70, access.cachedBattlegroundByRegion(null, "arena_2,1-battleground").orElseThrow().id());
        assertTrue(access.cachedBattlegroundByRegion("world_nether", "arena_2,1-battleground").isEmpty());
        assertTrue(access.cachedByKey("nope").isEmpty());
        assertEquals(calls, api.calls.get());
    }

    @Test
    void invalidateAllForcesTheNextReadToTheApi() {
        ArenasDataAccess access = new ArenasDataAccess(Duration.ofMinutes(15), api, NO_RETRY);
        access.getAllAsync().join();
        access.invalidateAll();

        assertTrue(access.getAllAsync().join().isFromApi());
        assertEquals(2, api.calls.get());
    }

    private static final class FakeArenasApi implements ArenasApi {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failing;

        @Override
        public CompletableFuture<List<KnkArena>> getAll() {
            calls.incrementAndGet();
            if (failing) return CompletableFuture.failedFuture(new IllegalStateException("API down"));
            return CompletableFuture.completedFuture(List.of(ArenaFixtures.arena(7, "arena_2",
                    List.of(new KnkArenaBattleground(70, 7, "bg1", "Pit", "arena_2,1-battleground", "world", 0, false, false,
                            null, null)),
                    List.of())));
        }
    }
}

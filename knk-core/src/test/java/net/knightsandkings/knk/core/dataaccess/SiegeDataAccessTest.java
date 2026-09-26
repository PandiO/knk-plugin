package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeReadiness;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeRuntimeConfig;
import net.knightsandkings.knk.core.ports.api.SiegeLobbiesQueryApi;
import net.knightsandkings.knk.core.ports.api.SiegeScenariosQueryApi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 4: the runtime-config gateway is cache-first and keeps the last copy when a refresh fails. */
class SiegeDataAccessTest {

    private static final DataAccessSettings NO_RETRY =
            new DataAccessSettings(FetchPolicy.CACHE_FIRST, true, RetryPolicy.noRetry());

    private final FakeLobbiesApi lobbiesApi = new FakeLobbiesApi();
    private final SiegeScenariosQueryApi scenariosApi = id -> CompletableFuture.completedFuture(
            id == 1 ? new KnkSiegeReadiness(1, true, false, List.of(), List.of()) : null);

    @Test
    void readsCacheFirst() {
        SiegeDataAccess access = new SiegeDataAccess(Duration.ofMinutes(30), lobbiesApi, scenariosApi, NO_RETRY);

        FetchResult<KnkSiegeRuntimeConfig> first = access.getRuntimeConfigAsync().join();
        FetchResult<KnkSiegeRuntimeConfig> second = access.getRuntimeConfigAsync().join();

        assertTrue(first.isFromApi());
        assertTrue(second.isFromCache());
        assertSame(first.orElseThrow(), second.orElseThrow());
        assertEquals(1, lobbiesApi.calls.get());
    }

    @Test
    void refreshAlwaysAsksTheApi() {
        SiegeDataAccess access = new SiegeDataAccess(Duration.ofMinutes(30), lobbiesApi, scenariosApi, NO_RETRY);
        access.getRuntimeConfigAsync().join();

        FetchResult<KnkSiegeRuntimeConfig> refreshed = access.refreshRuntimeConfigAsync().join();

        assertTrue(refreshed.isFromApi());
        assertEquals(2, lobbiesApi.calls.get());
        assertEquals(refreshed.orElseThrow(), access.cachedRuntimeConfig().orElseThrow());
    }

    @Test
    void failedRefreshKeepsThePreviousConfiguration() {
        SiegeDataAccess access = new SiegeDataAccess(Duration.ofMinutes(30), lobbiesApi, scenariosApi, NO_RETRY);
        KnkSiegeRuntimeConfig previous = access.getRuntimeConfigAsync().join().orElseThrow();
        lobbiesApi.failing = true;

        FetchResult<KnkSiegeRuntimeConfig> refreshed = access.refreshRuntimeConfigAsync().join();

        assertTrue(refreshed.isSuccess());
        assertSame(previous, refreshed.orElseThrow());
    }

    @Test
    void failedRefreshServesAnExpiredCopyAsStale() throws InterruptedException {
        SiegeDataAccess access = new SiegeDataAccess(Duration.ofMillis(1), lobbiesApi, scenariosApi, NO_RETRY);
        KnkSiegeRuntimeConfig previous = access.getRuntimeConfigAsync().join().orElseThrow();
        Thread.sleep(10);
        lobbiesApi.failing = true;

        FetchResult<KnkSiegeRuntimeConfig> refreshed = access.refreshRuntimeConfigAsync().join();

        assertTrue(refreshed.isStale());
        assertSame(previous, refreshed.orElseThrow());
    }

    @Test
    void failedFirstFetchIsAnError() {
        lobbiesApi.failing = true;
        SiegeDataAccess access = new SiegeDataAccess(Duration.ofMinutes(30), lobbiesApi, scenariosApi, NO_RETRY);

        FetchResult<KnkSiegeRuntimeConfig> result = access.refreshRuntimeConfigAsync().join();

        assertFalse(result.isSuccess());
        assertEquals(FetchStatus.ERROR, result.status());
        assertTrue(access.cachedRuntimeConfig().isEmpty());
    }

    @Test
    void readinessIsPassedThroughUncached() {
        SiegeDataAccess access = new SiegeDataAccess(Duration.ofMinutes(30), lobbiesApi, scenariosApi, NO_RETRY);

        assertTrue(access.getReadinessAsync(1).join().ready());
        assertEquals(null, access.getReadinessAsync(2).join());
    }

    private static final class FakeLobbiesApi implements SiegeLobbiesQueryApi {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failing;

        @Override
        public CompletableFuture<KnkSiegeRuntimeConfig> getRuntimeConfig() {
            int call = calls.incrementAndGet();
            if (failing) return CompletableFuture.failedFuture(new IllegalStateException("API down"));
            return CompletableFuture.completedFuture(new KnkSiegeRuntimeConfig(
                    Instant.ofEpochSecond(call), KnkSiegeConfiguration.legacyDefaults(), List.of()));
        }
    }
}

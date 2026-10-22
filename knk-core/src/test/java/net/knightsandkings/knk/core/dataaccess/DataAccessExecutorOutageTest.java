package net.knightsandkings.knk.core.dataaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.exception.ApiDownException;

/**
 * KNG-171: while the API is DOWN a read makes no API call and doesn't wait out retries; it ends
 * the way a failed call would. While it is UP (or UNKNOWN) everything is as before.
 */
class DataAccessExecutorOutageTest {

    private static final RetryPolicy FAST_RETRIES = RetryPolicy.builder()
        .maxAttempts(3).initialDelay(Duration.ofMillis(1)).maxDelay(Duration.ofMillis(1)).build();

    private final AtomicBoolean down = new AtomicBoolean(false);
    private final AtomicInteger calls = new AtomicInteger();

    private static final class TestCache extends BaseCache<String, String> {
        TestCache(Duration ttl) {
            super(ttl);
        }
    }

    private DataAccessExecutor<String, String> executor(BaseCache<String, String> cache) {
        DataAccessSettings settings = DataAccessSettings.builder()
            .retryPolicy(FAST_RETRIES)
            .apiOutage(down::get)
            .build();
        return new DataAccessExecutor<>(cache, settings, "Test");
    }

    private Supplier<CompletableFuture<String>> api(String value) {
        return () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(value);
        };
    }

    private Supplier<CompletableFuture<String>> unreachable() {
        return () -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new ConnectException("Connection refused"));
        };
    }

    @Test
    void whileDown_aMissMakesNoCallAndFailsAtOnce() {
        down.set(true);
        var executor = executor(new TestCache(Duration.ofMinutes(1)));

        CompletableFuture<FetchResult<String>> result = executor.fetchAsync("k", FetchPolicy.CACHE_FIRST, api("v"));

        assertTrue(result.isDone(), "answered without waiting");
        assertEquals(FetchStatus.ERROR, result.join().status());
        assertTrue(ApiDownException.isApiDown(result.join().error().orElseThrow()));
        assertEquals(0, calls.get());
    }

    @Test
    void whileDown_aFreshCacheHitIsAnsweredAsAlways() {
        TestCache cache = new TestCache(Duration.ofMinutes(1));
        cache.put("k", "cached");
        down.set(true);

        FetchResult<String> result = executor(cache).fetchAsync("k", FetchPolicy.CACHE_FIRST, api("v")).join();

        assertEquals(FetchStatus.HIT, result.status());
        assertEquals("cached", result.value().orElseThrow());
        assertEquals(0, calls.get());
    }

    @Test
    void whileDown_staleOkServesTheStaleEntry_andApiFirstFallsBackToTheCache() {
        // Already past its TTL: with Duration.ZERO the entry stays fresh until the clock ticks, which on Windows
        // is often not before the read.
        TestCache expired = new TestCache(Duration.ofNanos(-1));
        expired.put("k", "old");
        TestCache fresh = new TestCache(Duration.ofMinutes(1));
        fresh.put("k", "cached");
        down.set(true);

        FetchResult<String> stale = executor(expired).fetchAsync("k", FetchPolicy.STALE_OK, api("v")).join();
        FetchResult<String> apiFirst = executor(fresh).fetchAsync("k", FetchPolicy.API_THEN_CACHE_REFRESH, api("v")).join();
        FetchResult<String> apiOnly = executor(fresh).fetchAsync("k", FetchPolicy.API_ONLY, api("v")).join();

        assertEquals("old", stale.value().orElseThrow());
        assertTrue(stale.isStale());
        assertEquals("cached", apiFirst.value().orElseThrow());
        assertEquals(FetchStatus.ERROR, apiOnly.status());
        assertEquals(0, calls.get());
    }

    @Test
    void whileDown_blockingReadsMakeNoCallEither() {
        down.set(true);
        var executor = executor(new TestCache(Duration.ofMinutes(1)));

        FetchResult<String> result = executor.fetchBlocking("k", FetchPolicy.CACHE_FIRST, () -> {
            calls.incrementAndGet();
            return "v";
        });

        assertEquals(FetchStatus.ERROR, result.status());
        assertEquals(0, calls.get());
    }

    @Test
    void whileUp_aFailingCallIsRetriedAsBefore() {
        var executor = executor(new TestCache(Duration.ofMinutes(1)));

        FetchResult<String> result = executor.fetchAsync("k", FetchPolicy.CACHE_FIRST, unreachable()).join();

        assertEquals(FetchStatus.ERROR, result.status());
        assertEquals(3, calls.get(), "all three attempts");
    }

    @Test
    void aRetryScheduledBeforeTheStateTurnedDownIsNotSent() {
        var executor = executor(new TestCache(Duration.ofMinutes(1)));

        FetchResult<String> result = executor.fetchAsync("k", FetchPolicy.CACHE_FIRST, () -> {
            calls.incrementAndGet();
            down.set(true); // the probe flips to DOWN while the first attempt fails
            return CompletableFuture.failedFuture(new ConnectException("Connection refused"));
        }).join();

        assertEquals(FetchStatus.ERROR, result.status());
        assertEquals(1, calls.get());
    }

    @Test
    void backUp_callsResumeAndFillTheCache() {
        TestCache cache = new TestCache(Duration.ofMinutes(1));
        var executor = executor(cache);
        down.set(true);
        executor.fetchAsync("k", FetchPolicy.CACHE_FIRST, api("v")).join();

        down.set(false);
        FetchResult<String> result = executor.fetchAsync("k", FetchPolicy.CACHE_FIRST, api("v")).join();

        assertEquals(FetchStatus.MISS_FETCHED, result.status());
        assertEquals(1, calls.get());
        assertEquals("v", cache.get("k").orElseThrow());
    }

    @Test
    void withoutASignalTheApiIsNeverTreatedAsDown() {
        var executor = new DataAccessExecutor<>(new TestCache(Duration.ofMinutes(1)), RetryPolicy.noRetry(), "Test");

        assertEquals(FetchStatus.MISS_FETCHED, executor.fetchAsync("k", FetchPolicy.CACHE_FIRST, api("v")).join().status());
        assertEquals(1, calls.get());
    }
}

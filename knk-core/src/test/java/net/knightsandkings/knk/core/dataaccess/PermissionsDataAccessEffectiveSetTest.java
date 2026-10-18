package net.knightsandkings.knk.core.dataaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.permissions.EffectivePermission;
import net.knightsandkings.knk.core.domain.permissions.EffectivePermissionSet;
import net.knightsandkings.knk.core.domain.permissions.PermissionCheckResult;
import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.core.domain.permissions.PermissionHolder;
import net.knightsandkings.knk.core.domain.permissions.PermissionResolution;
import net.knightsandkings.knk.core.ports.api.PermissionsApi;

/**
 * KNG-107: on a fresh server {@code /knk help} sent one API check per subcommand. With checks resolved
 * from the effective set, any number of nodes costs one call per user per TTL.
 */
class PermissionsDataAccessEffectiveSetTest {

    private static final PermissionHolder USER = new PermissionHolder(7, "User", "tester");

    private final AtomicInteger effectiveCalls = new AtomicInteger();
    private final AtomicInteger checkCalls = new AtomicInteger();
    private final AtomicReference<CompletableFuture<EffectivePermissionSet>> effective = new AtomicReference<>(
        CompletableFuture.completedFuture(set(new EffectivePermission("knk.fly", true, USER, 0),
            new EffectivePermission("knk.heal.*", false, USER, 0))));
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private final PermissionsApi api = new PermissionsApi() {
        @Override
        public CompletableFuture<PermissionCheckResult> check(int userId, String node) {
            checkCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new PermissionCheckResult(node, PermissionResolution.GRANTED, USER, node));
        }

        @Override
        public CompletableFuture<EffectivePermissionSet> getEffective(int userId) {
            effectiveCalls.incrementAndGet();
            return effective.get();
        }
    };

    private final PermissionsDataAccess access = new PermissionsDataAccess(Duration.ofSeconds(30), api,
        new DataAccessSettings(FetchPolicy.CACHE_FIRST, true, RetryPolicy.noRetry()));

    PermissionsDataAccessEffectiveSetTest() {
        access.resolveFromEffectiveSets(Duration.ofSeconds(90), now::get);
    }

    private static EffectivePermissionSet set(EffectivePermission... entries) {
        return new EffectivePermissionSet(7, List.of(entries));
    }

    @Test
    void manyNodes_oneApiCall_resolvedLocally() {
        assertEquals(PermissionDecision.ALLOWED, access.decideAsync(7, "knk.fly").join());
        assertEquals(PermissionDecision.DENIED, access.decideAsync(7, "knk.heal.others").join());
        assertEquals(PermissionDecision.DENIED, access.decideAsync(7, "knk.admin.currency.lock").join());

        assertEquals(1, effectiveCalls.get());
        assertEquals(0, checkCalls.get());
    }

    @Test
    void concurrentChecksOnAColdCache_shareOneLoad() {
        CompletableFuture<EffectivePermissionSet> pending = new CompletableFuture<>();
        effective.set(pending);
        List<CompletableFuture<PermissionDecision>> decisions = new ArrayList<>();
        for (String node : List.of("knk.fly", "knk.heal", "knk.pay", "knk.baltop")) {
            decisions.add(access.decideAsync(7, node));
        }

        pending.complete(set(new EffectivePermission("knk.*", true, USER, 0)));

        decisions.forEach(decision -> assertEquals(PermissionDecision.ALLOWED, decision.join()));
        assertEquals(1, effectiveCalls.get());
    }

    @Test
    void cacheOnly_beforeAnyLoad_isAMiss_andMakesNoCall() {
        FetchResult<PermissionCheckResult> result = access.checkAsync(7, "knk.fly", FetchPolicy.CACHE_ONLY).join();

        assertFalse(result.isSuccess());
        assertEquals(0, effectiveCalls.get());
    }

    @Test
    void preload_letsCacheOnlyChecksAnswerAtOnce() {
        access.preload(7).join();

        assertTrue(access.checkAsync(7, "knk.fly", FetchPolicy.CACHE_ONLY).join().value().orElseThrow().isAllowed());
        assertEquals(1, effectiveCalls.get());
    }

    @Test
    void cacheOnly_afterTheTtl_answersFromTheLastSet_andReloadsIt() throws Exception {
        PermissionsDataAccess shortTtl = new PermissionsDataAccess(Duration.ofMillis(1), api,
            new DataAccessSettings(FetchPolicy.CACHE_FIRST, true, RetryPolicy.noRetry()));
        shortTtl.resolveFromEffectiveSets(Duration.ofSeconds(90), now::get);
        shortTtl.preload(7).join();
        Thread.sleep(5); // the cache's own TTL runs on the real clock

        FetchResult<PermissionCheckResult> stale = shortTtl.checkAsync(7, "knk.fly", FetchPolicy.CACHE_ONLY).join();

        assertTrue(stale.isSuccess());
        assertTrue(stale.isStale());
        assertEquals(2, effectiveCalls.get(), "reloaded in the background");
    }

    @Test
    void cacheOnly_longAfterTheTtl_stillAnswersFromTheLastSet_whileTheApiIsUp() throws Exception {
        PermissionsDataAccess shortTtl = new PermissionsDataAccess(Duration.ofMillis(1), api,
            new DataAccessSettings(FetchPolicy.CACHE_FIRST, true, RetryPolicy.noRetry()));
        shortTtl.resolveFromEffectiveSets(Duration.ofSeconds(90), now::get);
        effective.set(CompletableFuture.completedFuture(set(new EffectivePermission("knk.fly", true, USER, 0))));
        shortTtl.preload(7).join();
        effective.set(new CompletableFuture<>()); // reloads now hang, like a slow API
        Thread.sleep(5);
        now.addAndGet(Duration.ofMinutes(10).toMillis());

        assertTrue(shortTtl.hasLoadedSet(7));
        assertTrue(shortTtl.checkAsync(7, "knk.fly", FetchPolicy.CACHE_ONLY).join().value().orElseThrow().isAllowed());
    }

    @Test
    void cacheOnly_pastTheStaleWindow_whileTheApiIsDown_isAMiss() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(false);
        PermissionsDataAccess shortTtl = new PermissionsDataAccess(Duration.ofMillis(1), api,
            DataAccessSettings.builder().retryPolicy(RetryPolicy.noRetry()).apiOutage(down::get).build());
        shortTtl.resolveFromEffectiveSets(Duration.ofSeconds(90), now::get);
        shortTtl.preload(7).join();
        Thread.sleep(5);
        down.set(true);

        assertTrue(shortTtl.checkAsync(7, "knk.fly", FetchPolicy.CACHE_ONLY).join().isSuccess(), "within the window");
        now.addAndGet(Duration.ofSeconds(91).toMillis());
        assertFalse(shortTtl.checkAsync(7, "knk.fly", FetchPolicy.CACHE_ONLY).join().isSuccess());
        assertFalse(shortTtl.hasLoadedSet(7));
    }

    @Test
    void forget_dropsTheSet() {
        access.preload(7).join();
        assertTrue(access.hasLoadedSet(7));

        access.forget(7);

        assertFalse(access.hasLoadedSet(7));
        assertFalse(access.checkAsync(7, "knk.fly", FetchPolicy.CACHE_ONLY).join().isSuccess());
    }

    @Test
    void anUnknownUser_isDenied_andTheOfflineStoreIsToldToForgetThem() {
        effective.set(CompletableFuture.completedFuture(null)); // the API's 404
        List<String> heard = new ArrayList<>();
        access.setAnswerListener((userId, node, result) -> heard.add(userId + ":" + node + ":" + result));

        assertEquals(PermissionDecision.DENIED, access.decideAsync(7, "knk.fly").join());
        assertEquals(List.of("7:null:null"), heard);
    }

    @Test
    void whileTheApiIsDown_noSetIsLoaded_kng171() {
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
        PermissionsDataAccess gated = new PermissionsDataAccess(Duration.ofSeconds(30), api,
            DataAccessSettings.builder().retryPolicy(RetryPolicy.defaultPolicy()).apiOutage(down::get).build());
        gated.resolveFromEffectiveSets(Duration.ofSeconds(90), now::get);

        CompletableFuture<PermissionDecision> decision = gated.decideAsync(7, "knk.fly");

        assertTrue(decision.isDone(), "no wait for retries");
        assertEquals(PermissionDecision.UNAVAILABLE, decision.join());
        assertEquals(0, effectiveCalls.get());
        down.set(false);
        assertEquals(PermissionDecision.ALLOWED, gated.decideAsync(7, "knk.fly").join());
        assertEquals(1, effectiveCalls.get());
    }

    @Test
    void anUnreachableApi_isUnavailable() {
        effective.set(CompletableFuture.failedFuture(new RuntimeException("Connection refused")));

        assertEquals(PermissionDecision.UNAVAILABLE, access.decideAsync(7, "knk.fly").join());
    }

    @Test
    void anOlderApiWithoutRanks_fallsBackToSingleNodeChecks_forGood() {
        effective.set(CompletableFuture.completedFuture(set(new EffectivePermission("knk.fly", false, USER))));

        assertEquals(PermissionDecision.ALLOWED, access.decideAsync(7, "knk.fly").join());
        assertEquals(PermissionDecision.ALLOWED, access.decideAsync(7, "knk.heal").join());

        assertEquals(1, effectiveCalls.get());
        assertEquals(2, checkCalls.get());
    }

    @Test
    void theAnswerListener_hearsEachNodeOncePerLoadedSet() {
        List<String> heard = new ArrayList<>();
        access.setAnswerListener((userId, node, result) -> heard.add(node + "=" + result.isAllowed()));

        access.decideAsync(7, "knk.fly").join();
        access.decideAsync(7, "knk.fly").join();
        access.decideAsync(7, "knk.heal").join();
        access.invalidateEffective(7);
        access.decideAsync(7, "knk.fly").join();

        assertEquals(List.of("knk.fly=true", "knk.heal=false", "knk.fly=true"), heard);
        assertEquals(2, effectiveCalls.get());
    }

    @Test
    void invalidateEffective_makesTheNextCheckLoadAgain() {
        access.decideAsync(7, "knk.fly").join();
        effective.set(CompletableFuture.completedFuture(set(new EffectivePermission("knk.fly", false, USER, 0))));
        access.invalidateEffective(7);

        assertEquals(PermissionDecision.DENIED, access.decideAsync(7, "knk.fly").join());
        assertFalse(access.checkAsync(8, "knk.fly", FetchPolicy.CACHE_ONLY).join().isSuccess(), "other users untouched");
    }
}

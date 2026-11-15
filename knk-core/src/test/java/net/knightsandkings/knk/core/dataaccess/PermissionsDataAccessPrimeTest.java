package net.knightsandkings.knk.core.dataaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.permissions.EffectivePermission;
import net.knightsandkings.knk.core.domain.permissions.EffectivePermissionSet;
import net.knightsandkings.knk.core.domain.permissions.PermissionCheckResult;
import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.core.domain.permissions.PermissionHolder;
import net.knightsandkings.knk.core.ports.api.PermissionsApi;

/**
 * PandiO/knk-workspace#540: a scenario's fake player gets in-house grants by priming the live permission cache, with
 * no API behind it. The primed set answers like a set the API sent, and forgetting it drops it.
 */
class PermissionsDataAccessPrimeTest {

    private static final int FAKE_USER = -421_000_001;
    private static final PermissionHolder SELF = new PermissionHolder(FAKE_USER, "User", "ScnStaff");

    private final AtomicInteger apiCalls = new AtomicInteger();

    /** An API that is down: every call fails, as on the CI server. */
    private final PermissionsApi downApi = new PermissionsApi() {
        @Override
        public CompletableFuture<PermissionCheckResult> check(int userId, String node) {
            apiCalls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("API down"));
        }

        @Override
        public CompletableFuture<EffectivePermissionSet> getEffective(int userId) {
            apiCalls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("API down"));
        }
    };

    private PermissionsDataAccess access(boolean effectiveSets) {
        PermissionsDataAccess access = new PermissionsDataAccess(Duration.ofSeconds(30), downApi,
            new DataAccessSettings(FetchPolicy.CACHE_FIRST, true, RetryPolicy.noRetry()));
        if (effectiveSets) {
            access.resolveFromEffectiveSets(Duration.ofSeconds(90));
        }
        return access;
    }

    private static EffectivePermissionSet set(EffectivePermission... entries) {
        return new EffectivePermissionSet(FAKE_USER, List.of(entries));
    }

    @Test
    void aPrimedSetAnswersGrantsAndRefusesEverythingElseWithoutTheApi() {
        PermissionsDataAccess access = access(true);
        access.prime(set(new EffectivePermission("knk.heal", true, SELF, 0),
            new EffectivePermission("knk.heal.others", false, SELF, 0)));

        assertEquals(PermissionDecision.ALLOWED, access.decideAsync(FAKE_USER, "knk.heal").join());
        assertEquals(PermissionDecision.DENIED, access.decideAsync(FAKE_USER, "knk.heal.others").join());
        assertEquals(PermissionDecision.DENIED, access.decideAsync(FAKE_USER, "knk.teleport.staff").join(),
            "a node the set doesn't list is undeclared, so refused");
        assertTrue(access.checkAsync(FAKE_USER, "knk.heal", FetchPolicy.CACHE_ONLY).join().isSuccess(),
            "cache-only checks (the sync KnkPermissible path) answer from it too");
        assertTrue(access.hasLoadedSet(FAKE_USER));
        assertEquals(0, apiCalls.get());
    }

    @Test
    void anEmptySetRefusesEveryNode() {
        PermissionsDataAccess access = access(true);
        access.prime(set());

        assertEquals(PermissionDecision.DENIED, access.decideAsync(FAKE_USER, "knk.heal").join());
        assertEquals(0, apiCalls.get());
    }

    @Test
    void forgettingAPrimedSetAsksTheApiAgain() {
        PermissionsDataAccess access = access(true);
        access.prime(set(new EffectivePermission("knk.heal", true, SELF, 0)));

        access.forget(FAKE_USER);

        assertEquals(PermissionDecision.UNAVAILABLE, access.decideAsync(FAKE_USER, "knk.heal").join());
        assertTrue(apiCalls.get() > 0);
    }

    @Test
    void withSingleNodeChecksTheListedNodesAreCachedAnswers() {
        PermissionsDataAccess access = access(false);
        access.prime(set(new EffectivePermission("knk.heal", true, SELF, 0),
            new EffectivePermission("knk.feed", false, SELF, 0)));

        assertEquals(PermissionDecision.ALLOWED, access.decideAsync(FAKE_USER, "knk.heal").join());
        assertEquals(PermissionDecision.DENIED, access.decideAsync(FAKE_USER, "knk.feed").join());
        assertEquals(0, apiCalls.get());
    }
}

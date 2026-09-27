package net.knightsandkings.knk.core.dataaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.permissions.EffectivePermissionSet;
import net.knightsandkings.knk.core.domain.permissions.PermissionCheckResult;
import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.core.domain.permissions.PermissionResolution;
import net.knightsandkings.knk.core.ports.api.PermissionsApi;

/**
 * Currency smoke test: with knk-web-api down every non-op was told "You don't have permission"
 * - a failed check must read as UNAVAILABLE, not as a denial.
 */
class PermissionsDataAccessDecisionTest {

    private final AtomicReference<CompletableFuture<PermissionCheckResult>> answer = new AtomicReference<>();

    private final PermissionsApi api = new PermissionsApi() {
        @Override
        public CompletableFuture<PermissionCheckResult> check(int userId, String node) {
            return answer.get();
        }

        @Override
        public CompletableFuture<EffectivePermissionSet> getEffective(int userId) {
            return CompletableFuture.completedFuture(null);
        }
    };

    private final PermissionsDataAccess access = new PermissionsDataAccess(Duration.ofSeconds(30), api,
        new DataAccessSettings(FetchPolicy.CACHE_FIRST, true, RetryPolicy.noRetry()));

    private static PermissionCheckResult result(PermissionResolution resolution) {
        return new PermissionCheckResult("knk.pay", resolution, null, null);
    }

    @Test
    void aGrantIsAllowed_andADenyOrUndeclaredNodeIsDenied() {
        answer.set(CompletableFuture.completedFuture(result(PermissionResolution.GRANTED)));
        assertEquals(PermissionDecision.ALLOWED, access.decideAsync(1, "knk.pay").join());

        answer.set(CompletableFuture.completedFuture(result(PermissionResolution.DENIED)));
        assertEquals(PermissionDecision.DENIED, access.decideAsync(2, "knk.pay").join());

        answer.set(CompletableFuture.completedFuture(result(PermissionResolution.UNDECLARED)));
        assertEquals(PermissionDecision.DENIED, access.decideAsync(3, "knk.pay").join());
    }

    @Test
    void anUnknownUserIsDenied() {
        answer.set(CompletableFuture.completedFuture(null)); // the API's 404
        assertEquals(PermissionDecision.DENIED, access.decideAsync(4, "knk.pay").join());
    }

    @Test
    void anUnreachableApiIsUnavailable_notADenial() {
        answer.set(CompletableFuture.failedFuture(new RuntimeException("Connection refused")));
        assertEquals(PermissionDecision.UNAVAILABLE, access.decideAsync(5, "knk.pay").join());
    }

    @Test
    void aCachedAnswerStillCountsWhileTheApiIsDown() {
        answer.set(CompletableFuture.completedFuture(result(PermissionResolution.GRANTED)));
        access.decideAsync(6, "knk.pay").join();

        answer.set(CompletableFuture.failedFuture(new RuntimeException("Connection refused")));
        assertEquals(PermissionDecision.ALLOWED, access.decideAsync(6, "knk.pay").join());
    }
}

package net.knightsandkings.knk.paper.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.DataAccessSettings;
import net.knightsandkings.knk.core.dataaccess.FetchPolicy;
import net.knightsandkings.knk.core.dataaccess.PermissionsDataAccess;
import net.knightsandkings.knk.core.dataaccess.RetryPolicy;
import net.knightsandkings.knk.core.domain.permissions.EffectivePermissionSet;
import net.knightsandkings.knk.core.domain.permissions.PermissionCheckResult;
import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.core.domain.permissions.PermissionResolution;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.PermissionsApi;

/**
 * Currency smoke test: with knk-web-api down, non-ops were told "You don't have permission to do
 * that" for /pay, /balance... - checkAsync tells a real denial apart from a check that couldn't be made.
 */
class KnkPermissibleTest {

    private final AtomicReference<CompletableFuture<PermissionCheckResult>> answer = new AtomicReference<>();
    private final UserCache userCache = new UserCache(Duration.ofMinutes(15));
    private final KnkPermissible permissible = new KnkPermissible(userCache, new PermissionsDataAccess(Duration.ofSeconds(30),
        new PermissionsApi() {
            @Override
            public CompletableFuture<PermissionCheckResult> check(int userId, String node) {
                return answer.get();
            }

            @Override
            public CompletableFuture<EffectivePermissionSet> getEffective(int userId) {
                return CompletableFuture.completedFuture(null);
            }
        }, new DataAccessSettings(FetchPolicy.CACHE_FIRST, true, RetryPolicy.noRetry())));

    private OfflinePlayer player(boolean op, Integer userId) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        UUID uuid = UUID.randomUUID();
        when(player.isOp()).thenReturn(op);
        when(player.getUniqueId()).thenReturn(uuid);
        if (userId != null) {
            userCache.put(new UserSummary(userId, "p" + userId, uuid, 0));
        }
        return player;
    }

    private static CompletableFuture<PermissionCheckResult> resolved(PermissionResolution resolution) {
        return CompletableFuture.completedFuture(new PermissionCheckResult("knk.pay", resolution, null, null));
    }

    @Test
    void anOpIsAlwaysAllowed_evenWithTheApiDown() {
        answer.set(CompletableFuture.failedFuture(new RuntimeException("Connection refused")));
        assertEquals(PermissionDecision.ALLOWED, permissible.checkAsync(player(true, null), "knk.pay").join());
    }

    @Test
    void aGrantAndADenial_areRealAnswers() {
        answer.set(resolved(PermissionResolution.GRANTED));
        assertEquals(PermissionDecision.ALLOWED, permissible.checkAsync(player(false, 1), "knk.pay").join());

        answer.set(resolved(PermissionResolution.UNDECLARED));
        assertEquals(PermissionDecision.DENIED, permissible.checkAsync(player(false, 2), "knk.pay").join());
    }

    @Test
    void anUnreachableApi_orAnAccountThatNeverLoaded_isUnavailable() {
        answer.set(CompletableFuture.failedFuture(new RuntimeException("Connection refused")));
        OfflinePlayer loaded = player(false, 3);
        assertEquals(PermissionDecision.UNAVAILABLE, permissible.checkAsync(loaded, "knk.pay").join());
        assertFalse(permissible.hasPermissionAsync(loaded, "knk.pay").join()); // still fails closed

        assertEquals(PermissionDecision.UNAVAILABLE, permissible.checkAsync(player(false, null), "knk.pay").join());
    }
}

package net.knightsandkings.knk.paper.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import net.knightsandkings.knk.core.offline.OfflineSecurityStore;
import net.knightsandkings.knk.core.ports.api.PermissionsApi;

/**
 * KNG-58: with knk-web-api down - also right after a restart, when nothing is in memory - a
 * player's permissions are the API's last answers (bounded by age); unknown still means no.
 */
class KnkPermissibleOfflineTest {

    private static final UUID STAFF = UUID.randomUUID();
    private static final String NODE = "knk.region.bypass";

    private Instant now = Instant.parse("2026-10-08T12:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
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
    };
    private final OfflineSecurityStore store = new OfflineSecurityStore(null, OfflineSecurityStore.Settings.defaults(), clock);
    private final AtomicReference<CompletableFuture<PermissionCheckResult>> answer = new AtomicReference<>();

    /** A fresh server: empty memory caches, the same offline store (as loaded from disk). */
    private KnkPermissible server(Duration permissionTtl) {
        PermissionsDataAccess permissions = new PermissionsDataAccess(permissionTtl, new PermissionsApi() {
            @Override
            public CompletableFuture<PermissionCheckResult> check(int userId, String node) {
                return answer.get();
            }

            @Override
            public CompletableFuture<EffectivePermissionSet> getEffective(int userId) {
                return CompletableFuture.completedFuture(null);
            }
        }, new DataAccessSettings(FetchPolicy.CACHE_FIRST, true, RetryPolicy.noRetry()));
        // As KnKPlugin wires it.
        permissions.setAnswerListener((userId, node, result) -> {
            if (result == null) {
                store.forgetUserId(userId);
            } else {
                store.recordPermission(userId, node, result.isAllowed());
            }
        });
        return new KnkPermissible(new UserCache(Duration.ofMinutes(15)), permissions, store);
    }

    private static OfflinePlayer staff() {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(STAFF);
        return player;
    }

    private static CompletableFuture<PermissionCheckResult> granted() {
        return CompletableFuture.completedFuture(new PermissionCheckResult(NODE, PermissionResolution.GRANTED, null, null));
    }

    private static CompletableFuture<PermissionCheckResult> down() {
        return CompletableFuture.failedFuture(new RuntimeException("Connection refused"));
    }

    /** Before the outage: the API named the account and granted the node. */
    private void apiAnsweredBefore() {
        store.recordUser(new UserSummary(7, "Staffer", STAFF, 0));
        answer.set(granted());
        server(Duration.ofSeconds(30)).checkAsync(staff(), NODE).join();
    }

    @Test
    void afterARestartWithTheApiDown_staffKeepTheirLastKnownPermissions() {
        apiAnsweredBefore();
        answer.set(down());

        KnkPermissible restarted = server(Duration.ofSeconds(30));

        assertEquals(PermissionDecision.ALLOWED, restarted.checkAsync(staff(), NODE).join());
        assertTrue(restarted.hasPermission(staff(), NODE), "the synchronous check too");
    }

    @Test
    void aNodeTheApiNeverAnsweredIsStillUnavailable() {
        apiAnsweredBefore();
        answer.set(down());

        KnkPermissible restarted = server(Duration.ofSeconds(30));

        assertEquals(PermissionDecision.UNAVAILABLE, restarted.checkAsync(staff(), "knk.freeze").join());
        assertFalse(restarted.hasPermission(staff(), "knk.freeze"));
    }

    @Test
    void anAnswerOlderThanTheMaxAgeIsNotTrusted() {
        apiAnsweredBefore();
        answer.set(down());
        now = now.plus(Duration.ofHours(73));

        KnkPermissible restarted = server(Duration.ofSeconds(30));

        assertEquals(PermissionDecision.UNAVAILABLE, restarted.checkAsync(staff(), NODE).join());
        assertFalse(restarted.hasPermission(staff(), NODE));
    }

    @Test
    void aLastKnownDenialStaysADenial() {
        store.recordUser(new UserSummary(7, "Staffer", STAFF, 0));
        answer.set(CompletableFuture.completedFuture(new PermissionCheckResult(NODE, PermissionResolution.DENIED, null, null)));
        server(Duration.ofSeconds(30)).checkAsync(staff(), NODE).join();
        answer.set(down());

        assertEquals(PermissionDecision.DENIED, server(Duration.ofSeconds(30)).checkAsync(staff(), NODE).join());
    }

    @Test
    void withTheApiUp_theFirstCheckAfterTheMemoryTtlNoLongerFailsClosed() {
        apiAnsweredBefore();
        answer.set(granted());

        // TTL zero: the in-memory answer is always expired, as after 30 s.
        assertTrue(server(Duration.ZERO).hasPermission(staff(), NODE));
    }

    @Test
    void aCheckForAUserTheApiNoLongerKnowsForgetsThem() {
        apiAnsweredBefore();
        answer.set(CompletableFuture.completedFuture(null));  // 404: erased

        server(Duration.ofSeconds(30)).checkAsync(staff(), NODE).join();

        assertTrue(store.identity(STAFF).isEmpty());
        assertTrue(store.permission(7, NODE).isEmpty());
    }

    @Test
    void anUnknownPlayerIsUnavailableNotAllowed() {
        answer.set(down());

        assertEquals(PermissionDecision.UNAVAILABLE, server(Duration.ofSeconds(30)).checkAsync(staff(), NODE).join());
    }
}

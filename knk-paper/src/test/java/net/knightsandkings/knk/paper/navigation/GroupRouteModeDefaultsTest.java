package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.domain.users.GroupMembershipSummary;
import net.knightsandkings.knk.core.roads.route.RouteMode;

/** Off-road L7b (PandiO/knk-workspace#852): the per-player cache never blocks, fills in the background, drops on events. */
class GroupRouteModeDefaultsTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final List<CompletableFuture<List<GroupMembershipSummary>>> membershipCalls = new ArrayList<>();
    private List<PermissionGroupSummary> groups = List.of(group(7, 20, RouteMode.SHORTEST));
    private Instant now = Instant.parse("2026-11-20T12:00:00Z");

    private final GroupRouteModeDefaults defaults = new GroupRouteModeDefaults(
        uuid -> Map.of(alice, 42).get(uuid),
        userId -> {
            CompletableFuture<List<GroupMembershipSummary>> call = new CompletableFuture<>();
            membershipCalls.add(call);
            return call;
        },
        () -> CompletableFuture.completedFuture(groups),
        Duration.ofMinutes(10), new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        }, Logger.getLogger("test"));

    private static PermissionGroupSummary group(int id, int weight, RouteMode mode) {
        return new PermissionGroupSummary(id, "G" + id, weight, false, 1.0, null, null, null, mode);
    }

    private static List<GroupMembershipSummary> member(int... groupIds) {
        List<GroupMembershipSummary> out = new ArrayList<>();
        for (int id : groupIds) {
            out.add(new GroupMembershipSummary(id, "G" + id, 0, false, null, true));
        }
        return out;
    }

    @Test
    void aMissAnswersTodaysDefaultAndFillsInTheBackground() {
        assertNull(defaults.cachedDefault(alice), "cold: today's default, no wait");
        assertEquals(1, membershipCalls.size());
        assertNull(defaults.cachedDefault(alice), "still loading: one call per user at a time");
        assertEquals(1, membershipCalls.size());

        membershipCalls.get(0).complete(member(7));
        assertEquals(RouteMode.SHORTEST, defaults.cachedDefault(alice));
        assertEquals(1, membershipCalls.size(), "warm: no API call");
    }

    @Test
    void anUnknownPlayerIsNeverLookedUp() {
        assertNull(defaults.cachedDefault(stranger));
        assertNull(defaults.cachedDefault(null));
        assertEquals(0, membershipCalls.size());
    }

    @Test
    void noGroupDefaultIsCachedToo() {
        defaults.warm(alice);
        membershipCalls.get(0).complete(member(99));
        assertNull(defaults.cachedDefault(alice));
        assertEquals(1, membershipCalls.size());
    }

    @Test
    void aMembershipChangeOrAGroupChangeDropsTheEntry() {
        defaults.warm(alice);
        membershipCalls.get(0).complete(member(7));
        assertEquals(RouteMode.SHORTEST, defaults.cachedDefault(alice));

        groups = List.of(group(7, 20, RouteMode.EFFICIENT)); // an admin changed the group (PermissionGroupChanged)
        defaults.invalidateUser(42);
        assertNull(defaults.cachedDefault(alice));
        membershipCalls.get(1).complete(member(7));
        assertEquals(RouteMode.EFFICIENT, defaults.cachedDefault(alice));

        defaults.invalidateAll();
        assertNull(defaults.cachedDefault(alice));
        assertEquals(3, membershipCalls.size());
    }

    @Test
    void aFillThatStartedBeforeAnInvalidationIsDiscarded() {
        defaults.warm(alice);
        defaults.invalidateAll();
        membershipCalls.get(0).complete(member(7));
        assertNull(defaults.cachedDefault(alice), "the stale answer never lands");
        assertEquals(2, membershipCalls.size());
    }

    @Test
    void aFailedLookupKeepsTodaysDefaultAndRetries() {
        defaults.warm(alice);
        membershipCalls.get(0).completeExceptionally(new RuntimeException("API down"));
        assertNull(defaults.cachedDefault(alice));
        assertEquals(2, membershipCalls.size());
    }

    @Test
    void anExpiredEntryStillAnswersWhileItRefills() {
        defaults.warm(alice);
        membershipCalls.get(0).complete(member(7));
        now = now.plus(Duration.ofMinutes(11));
        assertEquals(RouteMode.SHORTEST, defaults.cachedDefault(alice));
        assertEquals(2, membershipCalls.size());
    }
}

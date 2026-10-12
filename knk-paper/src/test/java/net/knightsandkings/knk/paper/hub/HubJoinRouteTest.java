package net.knightsandkings.knk.paper.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkGroupOverride;
import net.knightsandkings.knk.core.domain.settings.KnkHubSettings;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.domain.users.PermissionGroupRef;

/** Decision 2026-10-12: the hub replaces only the server's default join spawn. */
class HubJoinRouteTest {

    private static final KnkSpawnReference LOUNGE = new KnkSpawnReference(KnkSpawnReference.SourceType.LOCATION, 3, "Lounge",
        new KnkLocation(3, "Lounge", 10.0, 64.0, 10.0, 0f, 0f, "world"));
    private static final PermissionGroupRef NOBLE = new PermissionGroupRef(2, "Noble");
    private static final PermissionGroupRef BUILDER = new PermissionGroupRef(4, "Builder");
    private static final PermissionGroupRef DEFAULT = new PermissionGroupRef(1, "Default");

    private static final KnkGameSettings SETTINGS = new KnkGameSettings("WorldSpawn", null, null, null, null, List.of(), null, null,
        List.of(
            new KnkGroupOverride(2, "Noble", 1, null, null, LOUNGE, null, false),
            new KnkGroupOverride(4, "Builder", 2, null, null, null, null, true)),
        KnkHubSettings.none());

    private static HubJoinRoute route(boolean apiDown, boolean exempt, boolean owner, List<PermissionGroupRef> groups) {
        return HubJoinRoute.decide(true, apiDown, exempt, owner, SETTINGS, groups, true);
    }

    @Test
    void withoutAHubNothingChanges() {
        assertEquals(HubJoinRoute.NO_HUB, HubJoinRoute.decide(false, true, false, false, SETTINGS, List.of(DEFAULT), true));
    }

    @Test
    void onlyTheServerDefaultIsReplacedByTheHub() {
        assertEquals(HubJoinRoute.HUB, route(false, false, false, List.of(DEFAULT)));
        assertEquals(HubJoinRoute.HUB, route(false, false, false, List.of()), "a new player");
        assertEquals(HubJoinRoute.GROUP_SPAWN, route(false, false, false, List.of(NOBLE, DEFAULT)));
        assertEquals(HubJoinRoute.LAST_LOCATION, route(false, false, false, List.of(BUILDER, NOBLE)), "the first group wins");
        assertEquals(HubJoinRoute.LAST_LOCATION, route(false, false, true, List.of(DEFAULT)), "owners stay where they logged out");
    }

    @Test
    void aGroupSpawnThatCannotBeReachedFallsBackToTheHub() {
        assertEquals(HubJoinRoute.HUB, HubJoinRoute.decide(true, false, false, false, SETTINGS, List.of(NOBLE), false));
    }

    @Test
    void duringAnOutageEveryJoinGoesToTheHubUnlessExempt() {
        assertEquals(HubJoinRoute.HUB, route(true, false, true, List.of(NOBLE)));
        assertEquals(HubJoinRoute.HUB, route(true, false, false, List.of(BUILDER)));
        assertEquals(HubJoinRoute.LAST_LOCATION, route(true, true, true, List.of(DEFAULT)));
    }
}

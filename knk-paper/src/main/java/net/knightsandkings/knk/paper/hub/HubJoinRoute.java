package net.knightsandkings.knk.paper.hub;

import java.util.List;

import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.users.PermissionGroupRef;
import net.knightsandkings.knk.core.settings.GroupOverrides;

/**
 * Where a joining player goes when a hub is selected (developer decision 2026-10-12, hub DESIGN §16.4 decision 8): the
 * hub replaces only the server's default join spawn. Owners and a group's "join where they logged out" keep the last
 * location, and a group's own join spawn wins, as without a hub. While the API is down every join goes to the hub
 * (§13: gameplay entry needs the API), unless the player is exempt by an active-mode exemption.
 */
public enum HubJoinRoute {
    /** No hub is selected: the join works as before. */
    NO_HUB,
    /** Placed at the hub spawn. */
    HUB,
    /** An owner, or a group that joins where they logged out: no teleport. */
    LAST_LOCATION,
    /** The player's group join spawn. */
    GROUP_SPAWN;

    /**
     * @param hubConfigured      a hub is selected
     * @param apiDown            the API is down (KNG-115 DOWN)
     * @param exemptDuringOutage the player is exempt from the outage rules (active staff/owner mode, ticked box)
     * @param owner              the player holds {@code knk.mode.owner} (ops included)
     * @param groupSpawnUsable   the player's group join spawn resolves to a spot in a loaded world
     */
    public static HubJoinRoute decide(boolean hubConfigured, boolean apiDown, boolean exemptDuringOutage, boolean owner,
                                      KnkGameSettings settings, List<PermissionGroupRef> groups, boolean groupSpawnUsable) {
        if (!hubConfigured) {
            return NO_HUB;
        }
        if (apiDown && !exemptDuringOutage) {
            return HUB;
        }
        if (owner || GroupOverrides.joinsAtLastLocation(settings, groups)) {
            return LAST_LOCATION;
        }
        if (GroupOverrides.joinSpawn(settings, groups).isPresent() && groupSpawnUsable) {
            return GROUP_SPAWN;
        }
        return HUB;
    }
}

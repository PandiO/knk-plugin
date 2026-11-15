package net.knightsandkings.knk.core.npc.nav.group;

import java.util.Objects;

import net.knightsandkings.knk.core.npc.nav.NavigationSettings;

/**
 * One group's resolved settings (see {@link NavigationSettings}; resolved once, for the group's leader, so a
 * template or an assignment can give a group its own formation: decision Q4).
 *
 * @param formation        the formation and its spacing
 * @param rejoinBlocks     a follower further than this from its lane plans its own leg back
 * @param regroupBlocks    the leader waits when a follower is this far behind its slot; 0 = never
 * @param catchUpSpeed     speed factor of a follower that is behind its slot; 1 = off
 * @param hopBlocks        the longest own leg back, in blocks (one local leg of the navigation service)
 * @param noProgressTicks  ticks without progress along the path before a follower counts as stuck
 * @param noRouteWaitTicks ticks between the tries of a follower whose own legs back keep failing
 * @param recheckTicks     ticks a rule verdict for the followers' lanes is kept (rule changes clear it at once)
 * @param maxRetries       a stuck follower's recovery steps before its own leg back is planned again (4b.2 item 9)
 * @param recoveryWaitTicks ticks between two recovery steps of a stuck follower
 * @param waypointMargin   how close a follower comes to a waypoint before heading for the next (4b.2 item 2)
 */
public record GroupSettings(Formation formation, double rejoinBlocks, double regroupBlocks, double catchUpSpeed,
                            double hopBlocks, int noProgressTicks, int noRouteWaitTicks, int recheckTicks,
                            int maxRetries, int recoveryWaitTicks, double waypointMargin) {

    public GroupSettings {
        Objects.requireNonNull(formation, "formation");
    }

    /** With the code defaults for the 4b.2 settings (recovery steps, their wait, the waypoint margin). */
    public GroupSettings(Formation formation, double rejoinBlocks, double regroupBlocks, double catchUpSpeed,
                         double hopBlocks, int noProgressTicks, int noRouteWaitTicks, int recheckTicks) {
        this(formation, rejoinBlocks, regroupBlocks, catchUpSpeed, hopBlocks, noProgressTicks, noRouteWaitTicks,
            recheckTicks, NavigationSettings.MAX_RETRIES.defaultValue(),
            NavigationSettings.RECOVERY_WAIT_TICKS.defaultValue(), NavigationSettings.WAYPOINT_MARGIN.defaultValue());
    }

    public static GroupSettings of(NavigationSettings settings, NavigationSettings.Scope scope) {
        Formation.Type type = Formation.Type.byName(settings.value(NavigationSettings.GROUP_FORMATION, scope));
        return new GroupSettings(new Formation(type == null ? Formation.Type.COLUMN : type,
            settings.value(NavigationSettings.GROUP_SPACING, scope),
            settings.value(NavigationSettings.GROUP_RANKS_WIDTH, scope)),
            settings.value(NavigationSettings.GROUP_REJOIN_BLOCKS, scope),
            settings.value(NavigationSettings.GROUP_REGROUP_BLOCKS, scope),
            settings.value(NavigationSettings.GROUP_CATCH_UP_SPEED, scope),
            settings.value(NavigationSettings.HOP_BLOCKS, scope),
            settings.value(NavigationSettings.NO_PROGRESS_TICKS, scope),
            settings.value(NavigationSettings.NO_ROUTE_WAIT_TICKS, scope),
            settings.value(NavigationSettings.RECHECK_TICKS, scope),
            settings.value(NavigationSettings.MAX_RETRIES, scope),
            settings.value(NavigationSettings.RECOVERY_WAIT_TICKS, scope),
            settings.value(NavigationSettings.WAYPOINT_MARGIN, scope));
    }

    public static GroupSettings defaults() {
        return of(NavigationSettings.defaults(), NavigationSettings.Scope.of(null));
    }

    public GroupSettings withFormation(Formation other) {
        return new GroupSettings(other, rejoinBlocks, regroupBlocks, catchUpSpeed, hopBlocks, noProgressTicks,
            noRouteWaitTicks, recheckTicks, maxRetries, recoveryWaitTicks, waypointMargin);
    }
}

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
 * @param widthHysteresisBlocks a narrower width (still 2 abreast or more) or a wider one is taken only when it lasts
 *                         this many blocks along the path (4b.3 item 5)
 * @param followersFinishTrip followers still on their way when the leader has arrived finish their trip to their
 *                         slot at the goal before they follow the group's next order (4b.3 items 8 to 11)
 * @param keeping          how the formation is kept: today's fixed widths or the elastic fit (4c.3 decision 2)
 */
public record GroupSettings(Formation formation, double rejoinBlocks, double regroupBlocks, double catchUpSpeed,
                            double hopBlocks, int noProgressTicks, int noRouteWaitTicks, int recheckTicks,
                            int maxRetries, int recoveryWaitTicks, double waypointMargin,
                            int widthHysteresisBlocks, boolean followersFinishTrip, Keeping keeping) {

    /**
     * How a group keeps its formation (implementation plan 4c.3 decision 2, link M3; {@code group-formation-keeping}).
     *
     * @param elastic           false: today's fixed widths ({@code levels}); true: the elastic fit
     * @param lateralSpacingMin the squeezed spacing between neighbours in a row ({@code group-lateral-spacing-min})
     * @param spacingMin        the squeezed gap between rows ({@code group-spacing-min})
     * @param lookaheadBlocks   blocks looked ahead past the formation's depth ({@code group-lookahead-blocks})
     * @param wallClearance     the preferred margin of the outer lanes to a wall or drop ({@code wall-clearance})
     */
    public record Keeping(boolean elastic, double lateralSpacingMin, double spacingMin, int lookaheadBlocks,
                          double wallClearance) {
        /** {@code levels}, the keys at their code defaults. */
        public static Keeping levels() {
            return new Keeping(false, NavigationSettings.GROUP_LATERAL_SPACING_MIN.defaultValue(),
                NavigationSettings.GROUP_SPACING_MIN.defaultValue(),
                NavigationSettings.GROUP_LOOKAHEAD_BLOCKS.defaultValue(),
                NavigationSettings.WALL_CLEARANCE.defaultValue());
        }

        /** {@code elastic}, the keys at their code defaults. */
        public static Keeping elasticDefaults() {
            Keeping l = levels();
            return new Keeping(true, l.lateralSpacingMin, l.spacingMin, l.lookaheadBlocks, l.wallClearance);
        }

        static Keeping of(NavigationSettings settings, NavigationSettings.Scope scope) {
            return new Keeping("elastic".equals(settings.value(NavigationSettings.GROUP_FORMATION_KEEPING, scope)),
                settings.value(NavigationSettings.GROUP_LATERAL_SPACING_MIN, scope),
                settings.value(NavigationSettings.GROUP_SPACING_MIN, scope),
                settings.value(NavigationSettings.GROUP_LOOKAHEAD_BLOCKS, scope),
                settings.value(NavigationSettings.WALL_CLEARANCE, scope));
        }
    }

    public GroupSettings {
        Objects.requireNonNull(formation, "formation");
        keeping = keeping == null ? Keeping.levels() : keeping;
    }

    /** With today's fixed widths ({@code group-formation-keeping: levels}). */
    public GroupSettings(Formation formation, double rejoinBlocks, double regroupBlocks, double catchUpSpeed,
                         double hopBlocks, int noProgressTicks, int noRouteWaitTicks, int recheckTicks,
                         int maxRetries, int recoveryWaitTicks, double waypointMargin,
                         int widthHysteresisBlocks, boolean followersFinishTrip) {
        this(formation, rejoinBlocks, regroupBlocks, catchUpSpeed, hopBlocks, noProgressTicks, noRouteWaitTicks,
            recheckTicks, maxRetries, recoveryWaitTicks, waypointMargin, widthHysteresisBlocks, followersFinishTrip,
            Keeping.levels());
    }

    /** With the code defaults for the 4b.3 settings (width hysteresis, followers finish their trip). */
    public GroupSettings(Formation formation, double rejoinBlocks, double regroupBlocks, double catchUpSpeed,
                         double hopBlocks, int noProgressTicks, int noRouteWaitTicks, int recheckTicks,
                         int maxRetries, int recoveryWaitTicks, double waypointMargin) {
        this(formation, rejoinBlocks, regroupBlocks, catchUpSpeed, hopBlocks, noProgressTicks, noRouteWaitTicks,
            recheckTicks, maxRetries, recoveryWaitTicks, waypointMargin,
            NavigationSettings.GROUP_WIDTH_HYSTERESIS_BLOCKS.defaultValue(),
            NavigationSettings.GROUP_FOLLOWERS_FINISH_TRIP.defaultValue());
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
            settings.value(NavigationSettings.GROUP_LATERAL_SPACING, scope),
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
            settings.value(NavigationSettings.WAYPOINT_MARGIN, scope),
            settings.value(NavigationSettings.GROUP_WIDTH_HYSTERESIS_BLOCKS, scope),
            settings.value(NavigationSettings.GROUP_FOLLOWERS_FINISH_TRIP, scope),
            Keeping.of(settings, scope));
    }

    public static GroupSettings defaults() {
        return of(NavigationSettings.defaults(), NavigationSettings.Scope.of(null));
    }

    public GroupSettings withFormation(Formation other) {
        return new GroupSettings(other, rejoinBlocks, regroupBlocks, catchUpSpeed, hopBlocks, noProgressTicks,
            noRouteWaitTicks, recheckTicks, maxRetries, recoveryWaitTicks, waypointMargin, widthHysteresisBlocks,
            followersFinishTrip, keeping);
    }

    /** The same settings with the toggle of 4b.3 item 8 set (the group command's {@code finish-trip}). */
    public GroupSettings withFollowersFinishTrip(boolean finish) {
        return new GroupSettings(formation, rejoinBlocks, regroupBlocks, catchUpSpeed, hopBlocks, noProgressTicks,
            noRouteWaitTicks, recheckTicks, maxRetries, recoveryWaitTicks, waypointMargin, widthHysteresisBlocks,
            finish, keeping);
    }

    /** The same settings with another way of keeping the formation (link M3; scenarios and tests). */
    public GroupSettings withKeeping(Keeping other) {
        return new GroupSettings(formation, rejoinBlocks, regroupBlocks, catchUpSpeed, hopBlocks, noProgressTicks,
            noRouteWaitTicks, recheckTicks, maxRetries, recoveryWaitTicks, waypointMargin, widthHysteresisBlocks,
            followersFinishTrip, other);
    }
}

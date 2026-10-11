package net.knightsandkings.knk.core.navigation;

/**
 * Guidance tunables with the DESIGN §4 / §6.4 defaults. Phase 3's {@code NavigationConfig} (reuse
 * map R16) fills it from {@code config.yml}; tests use {@link #defaults()}. Routing tunables live
 * in {@code core/roads/route/RouterParameters}.
 *
 * @param rerouteDistance            {@code reroute-distance} (8): farther than this (3D) from the
 *                                   route counts as off-route
 * @param rerouteAfterTicks          {@code reroute-after-ticks} (40): off-route for this many
 *                                   ticks → re-route
 * @param rerouteMinIntervalTicks    a re-route is requested at most once per this many ticks
 *                                   (DESIGN §6.4 "max once per 3 s" = 60)
 * @param improvementIntervalTicks   an "element opened, route may be shorter" re-route at most
 *                                   once per this many ticks (DESIGN §6.7 "once per 10 s" = 200)
 * @param improvementThreshold       a new route replaces the current one only when its length is
 *                                   below {@code (1 - threshold) × remaining} (DESIGN §6.7: 15 %)
 * @param arriveDistance             {@code arrive-distance} (4): within this of the goal = arrived
 * @param maxSessionMinutes          {@code max-session-minutes} (30): the session ends after this
 * @param sprintSpeed                {@code sprint-speed} (5.6 blocks/s) for the ETA
 * @param offRouteLookBackBlocks     how far behind the last progress point the projection may
 *                                   fall back (keeps progress monotone on self-crossing routes)
 */
public record SessionParameters(double rerouteDistance, int rerouteAfterTicks, int rerouteMinIntervalTicks,
                                int improvementIntervalTicks, double improvementThreshold, double arriveDistance,
                                int maxSessionMinutes, double sprintSpeed, double offRouteLookBackBlocks) {

    public static final double DEFAULT_REROUTE_DISTANCE = 8;
    public static final int DEFAULT_REROUTE_AFTER_TICKS = 40;
    public static final int DEFAULT_REROUTE_MIN_INTERVAL_TICKS = 60;
    public static final int DEFAULT_IMPROVEMENT_INTERVAL_TICKS = 200;
    public static final double DEFAULT_IMPROVEMENT_THRESHOLD = 0.15;
    public static final double DEFAULT_ARRIVE_DISTANCE = 4;
    public static final int DEFAULT_MAX_SESSION_MINUTES = 30;
    public static final double DEFAULT_SPRINT_SPEED = 5.6;
    public static final double DEFAULT_OFF_ROUTE_LOOK_BACK = 16;

    public SessionParameters {
        if (!(rerouteDistance > 0)) throw new IllegalArgumentException("rerouteDistance must be > 0");
        if (rerouteAfterTicks < 0) throw new IllegalArgumentException("rerouteAfterTicks must be >= 0");
        if (rerouteMinIntervalTicks < 0) throw new IllegalArgumentException("rerouteMinIntervalTicks must be >= 0");
        if (improvementIntervalTicks < 0) throw new IllegalArgumentException("improvementIntervalTicks must be >= 0");
        if (!(improvementThreshold >= 0 && improvementThreshold < 1)) {
            throw new IllegalArgumentException("improvementThreshold must be in [0, 1)");
        }
        if (!(arriveDistance > 0)) throw new IllegalArgumentException("arriveDistance must be > 0");
        if (maxSessionMinutes < 1) throw new IllegalArgumentException("maxSessionMinutes must be >= 1");
        if (!(sprintSpeed > 0)) throw new IllegalArgumentException("sprintSpeed must be > 0");
        if (!(offRouteLookBackBlocks >= 0)) throw new IllegalArgumentException("offRouteLookBackBlocks must be >= 0");
    }

    /** The DESIGN defaults. */
    public static SessionParameters defaults() {
        return new SessionParameters(DEFAULT_REROUTE_DISTANCE, DEFAULT_REROUTE_AFTER_TICKS,
            DEFAULT_REROUTE_MIN_INTERVAL_TICKS, DEFAULT_IMPROVEMENT_INTERVAL_TICKS, DEFAULT_IMPROVEMENT_THRESHOLD,
            DEFAULT_ARRIVE_DISTANCE, DEFAULT_MAX_SESSION_MINUTES, DEFAULT_SPRINT_SPEED, DEFAULT_OFF_ROUTE_LOOK_BACK);
    }

    public SessionParameters withReroute(double distance, int afterTicks, int minIntervalTicks) {
        return new SessionParameters(distance, afterTicks, minIntervalTicks, improvementIntervalTicks,
            improvementThreshold, arriveDistance, maxSessionMinutes, sprintSpeed, offRouteLookBackBlocks);
    }

    public SessionParameters withArriveDistance(double distance) {
        return new SessionParameters(rerouteDistance, rerouteAfterTicks, rerouteMinIntervalTicks,
            improvementIntervalTicks, improvementThreshold, distance, maxSessionMinutes, sprintSpeed,
            offRouteLookBackBlocks);
    }

    public SessionParameters withMaxSessionMinutes(int minutes) {
        return new SessionParameters(rerouteDistance, rerouteAfterTicks, rerouteMinIntervalTicks,
            improvementIntervalTicks, improvementThreshold, arriveDistance, minutes, sprintSpeed,
            offRouteLookBackBlocks);
    }

    /** {@code maxSessionMinutes} in ticks (20 per second). */
    public long maxSessionTicks() {
        return maxSessionMinutes * 60L * 20L;
    }
}

package net.knightsandkings.knk.core.roads.links;

import java.util.Locale;

/**
 * The developer's road-first wish (finding on #698, 2026-11-18, "Phase 0 live runs: road-first finding for O32";
 * off-road navigation DESIGN § 5.2, decision O12): a route a road can carry for most of the way uses the road for that
 * part, even when one walk search alone reaches the target. Today that question only arises in direct mode (a target
 * within {@code max-snap-distance}, which this rule never changes): there the single walk path is compared with the road
 * route plus its walks at both ends, and the road is taken when it carries at least {@code road-first-share} of the way
 * and the whole way is at most {@code road-first-max-ratio} times the walk path. Lengths in blocks (walked), the walks to
 * and from the road estimated as {@link #WALK_ESTIMATE} times their straight distance (as the join point of phase 6.2):
 * searching them first would cost two walk searches for a question asked once per navigation. Pure.
 *
 * @param share    {@code road-first-share}: the road's least part of the whole way, 0 to 1 (default 0.6, "most of the
 *                 way"; the developer confirms)
 * @param maxRatio {@code road-first-max-ratio}: the whole way's longest length in walk-path lengths, at least 1 (default
 *                 1.5, "a similar cost"; the developer confirms)
 */
public record RoadFirstRule(double share, double maxRatio) {

    public static final double DEFAULT_SHARE = 0.6;
    public static final double DEFAULT_MAX_RATIO = 1.5;
    /** The estimate of a walk to or from the road per block of straight distance. */
    public static final double WALK_ESTIMATE = 1.5;

    public RoadFirstRule {
        if (!(share >= 0 && share <= 1)) {
            throw new IllegalArgumentException("navigation.road-first-share must be 0..1 (got: " + share + ")");
        }
        if (!(maxRatio >= 1)) {
            throw new IllegalArgumentException("navigation.road-first-max-ratio must be at least 1 (got: " + maxRatio + ")");
        }
    }

    public static RoadFirstRule defaults() {
        return new RoadFirstRule(DEFAULT_SHARE, DEFAULT_MAX_RATIO);
    }

    /**
     * The decision and its numbers.
     *
     * @param useRoad   take the road route
     * @param roadShare the road's part of the whole way
     * @param ratio     the whole way in walk-path lengths
     * @param mixed     the whole way's estimated length (walk to the road, road, walk from it)
     */
    public record Decision(boolean useRoad, double roadShare, double ratio, double mixed) {
        public String describe() {
            return String.format(Locale.ROOT, "%s (road %.0f%% of %.0f blocks, %.2f x the walk path)",
                useRoad ? "road" : "walk", roadShare * 100, mixed, ratio);
        }
    }

    /**
     * @param walkPath  the single walk path's length (blocks)
     * @param toRoad    straight distance from the player to the road route's start
     * @param road      the road route's length (walked)
     * @param fromRoad  straight distance from the road route's end to the target
     */
    public Decision decide(double walkPath, double toRoad, double road, double fromRoad) {
        double mixed = WALK_ESTIMATE * Math.max(0, toRoad) + Math.max(0, road) + WALK_ESTIMATE * Math.max(0, fromRoad);
        double roadShare = mixed <= 0 ? 0 : Math.max(0, road) / mixed;
        double ratio = walkPath <= 0 ? Double.POSITIVE_INFINITY : mixed / walkPath;
        boolean useRoad = road > 0 && roadShare >= share && ratio <= maxRatio;
        return new Decision(useRoad, roadShare, ratio, mixed);
    }
}

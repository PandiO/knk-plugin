package net.knightsandkings.knk.core.roads.route;

import java.util.Locale;
import java.util.Optional;

/**
 * Off-road navigation phase 4 (PandiO/knk-workspace#707, DESIGN § 5.4, decisions O02, O14, O24, O25): which costs a
 * route is planned with, on road and walk legs alike.
 *
 * <ol>
 *     <li>{@link #SHORTEST}: length only. Road edges cost their length; a walk leg prices every move as the blocks
 *     walked (doors, water, drops and ladders stay passable, with no extra cost).</li>
 *     <li>{@link #EFFICIENT}: the road class cost ({@code class-cost}) and the walk profile's costs for stairs,
 *     ladders, doors, water and drops.</li>
 *     <li>{@link #PREFERRED} (the default): efficient times the preference multipliers, on the road the profile's and
 *     the edge's {@code costMultiplier}. This is today's formula (navigation DESIGN § 6.2,
 *     {@code length × classCost × profile.costMultiplier × edge.costMultiplier}) and today's walk profile, so the
 *     default reproduces today's routes exactly (O24). Landmark preference weights on walk cells join here in phase 5.2
 *     (#709, {@link #usesPreferenceWeights()}).</li>
 * </ol>
 *
 * <p>Bukkit-free (DESIGN § 11). NPC route plans never pass a mode and keep {@link #DEFAULT}.
 */
public enum RouteMode {
    SHORTEST,
    EFFICIENT,
    PREFERRED;

    /** The mode of {@code /navigate <destination>} without a mode word, and of every caller that names none. */
    public static final RouteMode DEFAULT = PREFERRED;

    /** The node prefix of the mode words: {@code knk.navigate.mode.<word>} (O25). */
    public static final String NODE_PREFIX = "knk.navigate.mode.";

    /** The word a player types ({@code shortest}, {@code efficient}, {@code preferred}). */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The permission node a typed word needs (O25); the default without a typed word needs none. */
    public String node() {
        return NODE_PREFIX + word();
    }

    /** The mode a typed word names, case-insensitive; empty for any other word. */
    public static Optional<RouteMode> fromWord(String word) {
        if (word == null) {
            return Optional.empty();
        }
        String w = word.trim().toLowerCase(Locale.ROOT);
        for (RouteMode mode : values()) {
            if (mode.word().equals(w)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    /**
     * The routing cost factor of a road edge in this mode (DESIGN § 5.4.2).
     *
     * @param edgeMultiplier    the edge's {@code costMultiplier}
     * @param profileMultiplier the edge profile's {@code costMultiplier} (1.0 without a profile)
     * @param classFactor       the configured {@code class-cost} of the profile's road class (1.0 without a profile or
     *                          without a configured cost)
     */
    public double roadFactor(double edgeMultiplier, double profileMultiplier, double classFactor) {
        return switch (this) {
            case SHORTEST -> 1.0;
            case EFFICIENT -> classFactor;
            // today's order of the products (RoadNetworkSnapshot.costFactor before phase 4), so the numbers are identical
            case PREFERRED -> edgeMultiplier * profileMultiplier * classFactor;
        };
    }

    /**
     * The routing cost of a cached walk link in this mode before the road factor: its walked length in
     * {@link #SHORTEST}, its walk cost (length plus the walk profile's penalties, today's) otherwise.
     */
    public double linkCost(WalkLink link) {
        return this == SHORTEST ? link.length() : link.walkCost();
    }

    /** Whether the walk search prices moves as length only (no door, water, drop, jump, wall or climb extras). */
    public boolean lengthOnlyWalk() {
        return this == SHORTEST;
    }

    /**
     * Seam for phase 5.2 (#709, DESIGN § 5.4.3): whether landmark {@code preference} weights multiply the cost of walk
     * cells. Only {@link #PREFERRED}; nothing reads it before #709.
     */
    public boolean usesPreferenceWeights() {
        return this == PREFERRED;
    }
}

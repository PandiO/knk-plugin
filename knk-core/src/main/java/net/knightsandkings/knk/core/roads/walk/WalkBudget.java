package net.knightsandkings.knk.core.roads.walk;

/**
 * The limits of one walk search (KNG-51 {@code LAST_MILE_PATHFINDING.md} §5, config
 * {@code navigation.walk.*} §9). Running out of either budget gives
 * {@link WalkResult.Status#FALLBACK}, never a partial path (decision §11-5).
 *
 * @param maxExpansions   cells the search may expand (config {@code max-expansions}, 20 000)
 * @param maxLengthFactor the path may be at most this many times the straight start→target distance
 *                        ({@code max-length-factor}, 1.75)
 * @param maxLength       and never longer than this many blocks ({@code max-length}, 96)
 * @param startSnap       the start cell is the nearest cell within this many blocks of the feet (2)
 * @param goalSnap        a target with no cell within this many blocks has no path (3)
 */
public record WalkBudget(int maxExpansions, double maxLengthFactor, double maxLength, int startSnap, int goalSnap) {

    /** The design defaults. */
    public static final WalkBudget DEFAULTS = new WalkBudget(20_000, 1.75, 96.0, 2, 3);

    public WalkBudget {
        if (maxExpansions < 1) {
            throw new IllegalArgumentException("maxExpansions must be positive: " + maxExpansions);
        }
        if (!(maxLengthFactor >= 1.0)) {
            throw new IllegalArgumentException("maxLengthFactor must be >= 1: " + maxLengthFactor);
        }
        if (!(maxLength > 0.0)) {
            throw new IllegalArgumentException("maxLength must be positive: " + maxLength);
        }
        if (startSnap < 0 || goalSnap < 0) {
            throw new IllegalArgumentException("snap radii must not be negative: " + startSnap + ", " + goalSnap);
        }
    }

    /** The length cap for a leg whose start and target are {@code straight} blocks apart. */
    public double lengthCap(double straight) {
        return Math.min(maxLength, maxLengthFactor * straight);
    }
}

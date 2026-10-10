package net.knightsandkings.knk.core.roads.walk;

/**
 * The limits of one walk search (KNG-51 {@code LAST_MILE_PATHFINDING.md} §5, config
 * {@code navigation.walk.*} §9). Running out of either budget gives
 * {@link WalkResult.Status#FALLBACK}, never a partial path (decision §11-5).
 *
 * @param maxExpansions   cells the search may expand (config {@code max-expansions}, 20 000)
 * @param maxLengthFactor the path may be at most this many times the straight start→target distance
 *                        ({@code max-length-factor}, 1.75)
 * @param maxLength       and never longer than this many blocks ({@code max-length}, 144: KNG-75 step 2a found the
 *                        96 of KNG-51 cut off 5 of 9 reachable 55-96 block legs; legs up to 48 blocks are unaffected)
 * @param detourAllowance but always at least this many blocks longer than the straight distance
 *                        ({@code detour-allowance}, 48): a short leg behind a building needs a detour of
 *                        several times its straight distance (live test 2026-10-07, finding N2)
 * @param climbAllowance  and this many blocks more per block of height between start and target, on top of
 *                        {@code maxLength} ({@code climb-allowance}, 5; KNG-108): a spiral stair takes 5-6 blocks
 *                        of walking per block of height (the Keep Tower Roof: 168 for 29), a ladder 1
 * @param startSnap       the start cell is the nearest cell within this many blocks of the feet (2)
 * @param goalSnap        a target with no cell within this many blocks has no path (3)
 */
public record WalkBudget(int maxExpansions, double maxLengthFactor, double maxLength, double detourAllowance,
                         double climbAllowance, int startSnap, int goalSnap) {

    /** The design defaults. */
    public static final WalkBudget DEFAULTS = new WalkBudget(20_000, 1.75, 144.0, 48.0, 5.0, 2, 3);

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
        if (!(detourAllowance >= 0.0) || Double.isInfinite(detourAllowance)) {
            throw new IllegalArgumentException("detourAllowance must be a number >= 0: " + detourAllowance);
        }
        if (!(climbAllowance >= 0.0) || Double.isInfinite(climbAllowance)) {
            throw new IllegalArgumentException("climbAllowance must be a number >= 0: " + climbAllowance);
        }
        if (startSnap < 0 || goalSnap < 0) {
            throw new IllegalArgumentException("snap radii must not be negative: " + startSnap + ", " + goalSnap);
        }
    }

    /** Without a climb allowance (callers from before KNG-108): the cap ignores height. */
    public WalkBudget(int maxExpansions, double maxLengthFactor, double maxLength, double detourAllowance,
                      int startSnap, int goalSnap) {
        this(maxExpansions, maxLengthFactor, maxLength, detourAllowance, 0.0, startSnap, goalSnap);
    }

    /** The length cap for a level leg: {@link #lengthCap(double, double)} with no height difference. */
    public double lengthCap(double straight) {
        return lengthCap(straight, 0.0);
    }

    /**
     * The length cap for a leg whose start and target are {@code straight} blocks apart and {@code rise} blocks
     * apart in height (up or down): the factor or the detour allowance, whichever allows more, never above
     * {@code maxLength}; plus {@code climbAllowance} per block of {@code rise}, which may go above {@code maxLength}.
     */
    public double lengthCap(double straight, double rise) {
        return Math.min(maxLength, Math.max(maxLengthFactor * straight, straight + detourAllowance))
            + climbAllowance * Math.abs(rise);
    }
}

package net.knightsandkings.knk.core.roads.walk;

import java.util.Objects;

/**
 * What one walk search cost (off-road navigation phase 0, PandiO/knk-workspace#703; DESIGN § 9.2): the counters
 * that set the budgets of § 9.3. Measuring only: the search's result does not depend on them.
 *
 * @param expansions   cells the search expanded (the same number as {@link WalkResult#expansions()})
 * @param cells        cells the search touched: every cell it put on the open list at least once, expanded or not
 * @param searchNanos  wall time of {@link WalkSearch#find}, from the call to the result, in nanoseconds
 * @param cap          the budget that ended the search, or {@link Cap#NONE} when it ended on its own
 * @param lengthPruned true when at least one step was dropped because the path would have grown past the length
 *                     cap ({@code max-length} and friends), even when the search still arrived; a raised
 *                     {@code max-length} could then give another path (option A of DESIGN § 5.6)
 */
public record WalkSearchStats(int expansions, int cells, long searchNanos, Cap cap, boolean lengthPruned) {

    /** The budget that ended a search. */
    public enum Cap {
        /** Arrived, or the reachable area was searched completely. */
        NONE,
        /** {@code navigation.walk.max-expansions} ran out (FALLBACK "expansion budget"). */
        MAX_EXPANSIONS,
        /** Only steps past the length cap were left (FALLBACK "length cap"; {@code max-length}). */
        MAX_LENGTH
    }

    /** No numbers: a result that did not come from {@link WalkSearch} (a test fake, a cancelled search). */
    public static final WalkSearchStats NONE = new WalkSearchStats(0, 0, 0L, Cap.NONE, false);

    public WalkSearchStats {
        Objects.requireNonNull(cap, "cap");
        if (expansions < 0 || cells < 0 || searchNanos < 0) {
            throw new IllegalArgumentException("counters must not be negative: " + expansions + ", " + cells + ", "
                + searchNanos);
        }
    }

    /** {@link #searchNanos} in whole microseconds. */
    public long searchMicros() {
        return searchNanos / 1_000L;
    }
}

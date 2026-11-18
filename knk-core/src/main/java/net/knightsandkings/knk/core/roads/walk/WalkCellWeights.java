package net.knightsandkings.knk.core.roads.walk;

/**
 * Preference multipliers on walk cells (off-road navigation phase 5.2, PandiO/knk-workspace#709, DESIGN § 5.4.3 and
 * § 5.4.4): the cost of entering a cell is multiplied by {@link #multiplier}, and the search's heuristic is scaled by
 * {@link #minMultiplier()} so it stays admissible. Built per request before the search (Bukkit-free, immutable), read
 * on the walk thread.
 *
 * <p>{@link #NONE} is the search as before: no multiplier is applied and the heuristic is unscaled.
 */
public interface WalkCellWeights {

    /** No weights: every cell costs what the profile says. */
    WalkCellWeights NONE = new WalkCellWeights() {
        @Override
        public double multiplier(int x, int feetY, int z) {
            return 1.0;
        }

        @Override
        public double minMultiplier() {
            return 1.0;
        }

        @Override
        public boolean isNone() {
            return true;
        }

        @Override
        public String toString() {
            return "WalkCellWeights.NONE";
        }
    };

    /**
     * The multiplier of the cell whose feet block is {@code (x, feetY, z)} (the {@link CellAccess} convention): above 0
     * and finite; 1.0 outside every weighted area.
     */
    double multiplier(int x, int feetY, int z);

    /** A lower bound of {@link #multiplier} over every cell the search can reach; at most 1.0. */
    double minMultiplier();

    /** Whether no cell is weighted (the search then runs exactly as without weights). */
    default boolean isNone() {
        return false;
    }
}

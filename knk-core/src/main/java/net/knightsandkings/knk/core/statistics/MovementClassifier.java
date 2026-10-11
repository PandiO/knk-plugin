package net.knightsandkings.knk.core.statistics;

/**
 * Distance statistics (DESIGN.md §F.9, L1-13): which distance metric one movement segment counts
 * towards. A segment is one move event's from→to within one world, not a teleport, at most
 * {@code maxSegmentBlocks} long (longer = a server-side relocation, not travel). In a vehicle (boat,
 * minecart, mount) → vehicle; gliding with an elytra or flying → flying; otherwise on foot, where
 * swimming also adds to the internal {@code distance.swim}. Pure and allocation-free (it runs per
 * move event).
 */
public final class MovementClassifier {

    /** The metric a segment counts for. */
    public enum Mode {
        FOOT(StatisticsMetric.DISTANCE_FOOT),
        /** On foot while swimming: counts for foot and for the internal swim distance. */
        SWIM(StatisticsMetric.DISTANCE_FOOT),
        FLYING(StatisticsMetric.DISTANCE_FLYING),
        VEHICLE(StatisticsMetric.DISTANCE_VEHICLE);

        private final StatisticsMetric metric;

        Mode(StatisticsMetric metric) {
            this.metric = metric;
        }

        public StatisticsMetric metric() {
            return metric;
        }
    }

    private MovementClassifier() {
    }

    /**
     * @return the mode, or null when the segment doesn't count (zero/negative/NaN length or longer
     *         than {@code maxSegmentBlocks})
     */
    public static Mode classify(boolean inVehicle, boolean gliding, boolean flying, boolean swimming,
                                double segmentLength, double maxSegmentBlocks) {
        if (!(segmentLength > 0) || segmentLength > maxSegmentBlocks) {
            return null;
        }
        if (inVehicle) {
            return Mode.VEHICLE;
        }
        if (gliding || flying) {
            return Mode.FLYING;
        }
        return swimming ? Mode.SWIM : Mode.FOOT;
    }

    /** Euclidean length of the segment (horizontal and vertical). */
    public static double length(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}

package net.knightsandkings.knk.paper.npcspike;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * #243 spike (throwaway, never merged): benchmark counters and samples. Main thread only, except the
 * examiner counters, which live in {@link SpikeExaminer}.
 *
 * <p>Samples are kept in fixed ring buffers so a long run stays bounded; percentiles are over the
 * buffer (the most recent samples).
 */
final class SpikeMetrics {

    /** A ring buffer of the last {@code capacity} samples, for p50/p95/max. */
    static final class Samples {
        private final double[] values;
        private int next;
        private int size;
        private long total;

        Samples(int capacity) {
            this.values = new double[capacity];
        }

        void add(double value) {
            values[next] = value;
            next = (next + 1) % values.length;
            size = Math.min(size + 1, values.length);
            total++;
        }

        long total() {
            return total;
        }

        double mean() {
            if (size == 0) {
                return Double.NaN;
            }
            double sum = 0;
            for (int i = 0; i < size; i++) {
                sum += values[i];
            }
            return sum / size;
        }

        double percentile(double p) {
            if (size == 0) {
                return Double.NaN;
            }
            double[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            int index = (int) Math.ceil(p / 100.0 * size) - 1;
            return sorted[Math.max(0, Math.min(size - 1, index))];
        }

        double max() {
            return percentile(100);
        }

        void clear() {
            next = 0;
            size = 0;
            total = 0;
        }

        String describe(String unit) {
            if (size == 0) {
                return "-";
            }
            return String.format(Locale.ROOT, "p50 %.1f / p95 %.1f / max %.1f %s (n=%d)", percentile(50),
                percentile(95), max(), unit, total);
        }
    }

    /** Server tick durations (ms), from Paper's ServerTickEndEvent: the last 10 s. */
    final Samples tickMs = new Samples(200);
    /** The spike's own main-thread time per tick (µs): controller tick + its event handlers. */
    final Samples spikeMainUs = new Samples(200);
    /** K&K planning: request queued → Citizens target set (ms), queue wait included. */
    final Samples planMs = new Samples(2000);
    /** Queue wait alone (ms): request queued → planning started. */
    final Samples queueMs = new Samples(2000);
    /** Citizens' own A* (hop mode): target set → path available (ms). */
    final Samples citizensPathMs = new Samples(2000);
    /** Full trips from one route end to the other (s). */
    final Samples tripSeconds = new Samples(500);
    /** K&K walk search expansions per dense plan. */
    final Samples expansions = new Samples(2000);

    long spawned;
    long trips;
    long hops;
    long stuck;
    long replans;
    long pausedChunkUnload;
    long resumed;
    long recheckReplans;
    long examinerLeaks;
    final Map<String, Long> failures = new TreeMap<>();

    /** MSPT measured with no spike NPCs, set by {@code /knk npcspike baseline}. */
    double baselineMs = Double.NaN;

    long spikeNanosThisTick;

    void fail(String reason) {
        failures.merge(reason, 1L, Long::sum);
    }

    void reset() {
        tickMs.clear();
        spikeMainUs.clear();
        planMs.clear();
        queueMs.clear();
        citizensPathMs.clear();
        tripSeconds.clear();
        expansions.clear();
        trips = hops = stuck = replans = pausedChunkUnload = resumed = recheckReplans = examinerLeaks = 0;
        failures.clear();
        SpikeExaminer.CALLS.reset();
        SpikeExaminer.NANOS.reset();
        SpikeExaminer.BLOCKED.reset();
    }
}

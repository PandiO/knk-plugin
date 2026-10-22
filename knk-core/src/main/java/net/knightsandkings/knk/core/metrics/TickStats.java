package net.knightsandkings.knk.core.metrics;

import java.util.Arrays;

/** Average, 95th percentile and maximum of the server's recent tick durations (nanoseconds in, seconds out). */
public record TickStats(double avgSeconds, double p95Seconds, double maxSeconds, int samples) {

    public static final TickStats EMPTY = new TickStats(0, 0, 0, 0);

    /** From Paper's {@code Bukkit.getTickTimes()} (the last 100 ticks, nanoseconds; zero entries are unused slots). */
    public static TickStats fromNanos(long[] tickTimes) {
        if (tickTimes == null) {
            return EMPTY;
        }
        long[] used = Arrays.stream(tickTimes).filter(t -> t > 0).sorted().toArray();
        if (used.length == 0) {
            return EMPTY;
        }
        double sum = 0;
        for (long t : used) {
            sum += t;
        }
        // Nearest-rank percentile.
        int rank = (int) Math.ceil(0.95 * used.length);
        long p95 = used[Math.max(0, rank - 1)];
        return new TickStats(sum / used.length / 1e9, p95 / 1e9, used[used.length - 1] / 1e9, used.length);
    }
}

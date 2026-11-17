package net.knightsandkings.knk.core.roads.walk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * The walk-search measurements of off-road navigation phase 0 (PandiO/knk-workspace#703; DESIGN § 9.2): one
 * {@link Sample} per walk request that reached the search, kept for {@code /knk road status}. It holds the last
 * {@link #WINDOW} samples and reports the most recent ones and aggregates over them (max, p95, p50), plus totals
 * since the last {@link #reset()}.
 *
 * <p>Measuring only: nothing reads these numbers to decide a route. Thread-safe (the walk threads record, the main
 * thread reads); {@link #record} is one synchronized array store, so it costs nothing next to a search.
 */
public final class WalkMeasurements {

    /** Samples kept for the aggregates and the recent list. */
    public static final int WINDOW = 512;

    /**
     * What the capture of one request cost on the main thread (knk-paper {@code WalkSnapshotService}).
     *
     * @param chunks       chunks in the request's box
     * @param cacheHits    of those, chunks taken from the shared TTL cache (no capture work)
     * @param captureNanos main-thread time spent extracting the request's other chunks; a chunk captured once for
     *                     two waiting requests counts for both
     * @param waitMillis   from the request to the capture being ready (spread over ticks)
     */
    public record Capture(int chunks, int cacheHits, long captureNanos, long waitMillis) {

        /** Not known (a preparer that does not report its capture). */
        public static final Capture UNKNOWN = new Capture(-1, -1, -1L, -1L);

        public boolean known() {
            return chunks >= 0;
        }

        public long captureMicros() {
            return captureNanos < 0 ? -1L : captureNanos / 1_000L;
        }
    }

    /**
     * One searched walk request.
     *
     * @param seq         running number since the server started (not reset by {@link #reset()})
     * @param status      the search's outcome
     * @param search      the search's counters ({@link WalkResult#stats()})
     * @param capture     the capture's counters, {@link Capture#UNKNOWN} when not reported
     * @param queueNanos  time the search waited for a free walk thread ({@code max-concurrent-searches})
     * @param straight    straight distance from start to target, in blocks
     * @param pathLength  length of the path found, or of the partial path; 0 when there is none
     */
    public record Sample(long seq, WalkResult.Status status, WalkSearchStats search, Capture capture, long queueNanos,
                         double straight, double pathLength) {
        public Sample {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(search, "search");
            Objects.requireNonNull(capture, "capture");
        }

        /** One line for the recent list. */
        public String describe() {
            StringBuilder s = new StringBuilder();
            s.append('#').append(seq).append(' ').append(status);
            if (search.cap() != WalkSearchStats.Cap.NONE) {
                s.append(" (").append(capName(search.cap())).append(')');
            }
            s.append(String.format(Locale.ROOT, ", %.0f blocks straight", straight));
            if (pathLength > 0) {
                s.append(String.format(Locale.ROOT, ", path %.0f", pathLength));
            }
            s.append(", ").append(search.expansions()).append(" expansions, ").append(search.cells()).append(" cells");
            if (capture.known()) {
                s.append(", ").append(capture.chunks()).append(" chunks (").append(capture.cacheHits())
                    .append(" cached), capture ").append(capture.captureMicros()).append(" µs");
            }
            s.append(", search ").append(search.searchMicros()).append(" µs");
            if (search.lengthPruned() && search.cap() != WalkSearchStats.Cap.MAX_LENGTH) {
                s.append(", length-pruned");
            }
            return s.toString();
        }
    }

    /** Max, 95th and 50th percentile (nearest rank) of one counter over the window. */
    public record Spread(long max, long p95, long p50) {
        static final Spread EMPTY = new Spread(0, 0, 0);

        @Override
        public String toString() {
            return "max " + max + ", p95 " + p95 + ", p50 " + p50;
        }
    }

    /**
     * The aggregates over the window.
     *
     * @param total        searched requests since the last reset (may exceed the window)
     * @param window       samples the spreads are taken over
     * @param found        FOUND in the window
     * @param noPath       NO_PATH in the window
     * @param fallback     FALLBACK in the window
     * @param maxExpansionsCap ended by {@code max-expansions} in the window
     * @param maxLengthCap ended by the length cap in the window
     * @param lengthPruned searches that dropped steps at the length cap in the window (whatever the outcome)
     * @param searchMicros search time
     * @param captureMicros capture time, over the samples with a known capture
     * @param waitMillis   capture wait, over the samples with a known capture
     * @param queueMicros  wait for a walk thread
     * @param expansions   expansions
     * @param cells        cells touched
     * @param chunks       chunks per request, over the samples with a known capture
     */
    public record Summary(long total, int window, int found, int noPath, int fallback, int maxExpansionsCap,
                          int maxLengthCap, int lengthPruned, Spread searchMicros, Spread captureMicros,
                          Spread waitMillis, Spread queueMicros, Spread expansions, Spread cells, Spread chunks) {
    }

    private final Sample[] ring = new Sample[WINDOW];
    private int next;
    private int size;
    private long total;
    private long seq;

    /** Records one searched request; returns the stored sample (with its running number). */
    public synchronized Sample record(WalkResult.Status status, WalkSearchStats search, Capture capture,
                                      long queueNanos, double straight, double pathLength) {
        Sample sample = new Sample(++seq, status, search == null ? WalkSearchStats.NONE : search,
            capture == null ? Capture.UNKNOWN : capture, Math.max(0L, queueNanos), straight, pathLength);
        ring[next] = sample;
        next = (next + 1) % WINDOW;
        size = Math.min(size + 1, WINDOW);
        total++;
        return sample;
    }

    /** Forgets every sample (one scene at a time: reset, walk, read). The running number goes on. */
    public synchronized void reset() {
        Arrays.fill(ring, null);
        next = 0;
        size = 0;
        total = 0;
    }

    /** The most recent samples, newest first, at most {@code limit}. */
    public synchronized List<Sample> recent(int limit) {
        int n = Math.min(Math.max(0, limit), size);
        List<Sample> out = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            out.add(ring[(next - i + WINDOW) % WINDOW]);
        }
        return out;
    }

    public synchronized Summary summary() {
        List<Sample> all = recent(size);
        int found = 0;
        int noPath = 0;
        int fallback = 0;
        int expansionsCap = 0;
        int lengthCap = 0;
        int pruned = 0;
        List<Sample> captured = new ArrayList<>();
        for (Sample s : all) {
            switch (s.status()) {
                case FOUND -> found++;
                case NO_PATH -> noPath++;
                case FALLBACK -> fallback++;
            }
            switch (s.search().cap()) {
                case MAX_EXPANSIONS -> expansionsCap++;
                case MAX_LENGTH -> lengthCap++;
                case NONE -> { }
            }
            if (s.search().lengthPruned()) {
                pruned++;
            }
            if (s.capture().known()) {
                captured.add(s);
            }
        }
        return new Summary(total, all.size(), found, noPath, fallback, expansionsCap, lengthCap, pruned,
            spread(all, s -> s.search().searchMicros()),
            spread(captured, s -> s.capture().captureMicros()),
            spread(captured, s -> s.capture().waitMillis()),
            spread(all, s -> s.queueNanos() / 1_000L),
            spread(all, s -> s.search().expansions()),
            spread(all, s -> s.search().cells()),
            spread(captured, s -> s.capture().chunks()));
    }

    /**
     * The lines {@code /knk road status} shows under "walk search": the aggregates, then the {@code recent} newest
     * samples. One line "none since the last reset" when there is nothing.
     */
    public List<String> describe(int recent) {
        Summary s = summary();
        List<String> lines = new ArrayList<>();
        if (s.total() == 0) {
            lines.add("no searches since the last reset");
            return lines;
        }
        lines.add(s.total() + " searches since the last reset; last " + s.window() + ": found " + s.found()
            + ", no path " + s.noPath() + ", budget " + s.fallback() + " (max-expansions " + s.maxExpansionsCap()
            + ", max-length " + s.maxLengthCap() + "); length-pruned " + s.lengthPruned());
        lines.add("search µs: " + s.searchMicros() + "; queue µs: " + s.queueMicros());
        lines.add("expansions: " + s.expansions() + "; cells: " + s.cells());
        lines.add("capture µs: " + s.captureMicros() + "; chunks: " + s.chunks() + "; wait ms: " + s.waitMillis());
        for (Sample sample : recent(recent)) {
            lines.add(sample.describe());
        }
        return lines;
    }

    static Spread spread(List<Sample> samples, ToLongFunction<Sample> value) {
        if (samples.isEmpty()) {
            return Spread.EMPTY;
        }
        long[] values = new long[samples.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = value.applyAsLong(samples.get(i));
        }
        Arrays.sort(values);
        return new Spread(values[values.length - 1], rank(values, 0.95), rank(values, 0.50));
    }

    /** Nearest-rank percentile of sorted values. */
    static long rank(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static String capName(WalkSearchStats.Cap cap) {
        return cap == WalkSearchStats.Cap.MAX_EXPANSIONS ? "max-expansions" : "max-length";
    }
}

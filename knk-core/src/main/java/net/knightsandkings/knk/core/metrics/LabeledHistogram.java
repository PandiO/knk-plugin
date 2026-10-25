package net.knightsandkings.knk.core.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * A Prometheus histogram with fixed second-scale buckets and a fixed set of label names, safe to observe
 * from any thread. The number of distinct label combinations is capped (extra combinations fold into a
 * single {@code other} series) so a bug that puts an id in a label cannot grow memory without bound.
 */
public final class LabeledHistogram implements MetricsCollector {

    /** Seconds, 0.5 ms .. 10 s: a tick is 50 ms, an API call is usually 5 ms to 1 s. */
    public static final double[] DEFAULT_BOUNDS = {0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};

    static final int MAX_SERIES = 200;

    private static final class Series {
        final AtomicLongArray buckets;
        final DoubleAdder sum = new DoubleAdder();

        Series(int bounds) {
            buckets = new AtomicLongArray(bounds + 1);
        }
    }

    private final String name;
    private final String help;
    private final String[] labelNames;
    private final double[] bounds;
    private final Map<String, Series> series = new ConcurrentHashMap<>();
    private final Map<String, String[]> labelValuesByKey = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    public LabeledHistogram(String name, String help, String... labelNames) {
        this(name, help, DEFAULT_BOUNDS, labelNames);
    }

    public LabeledHistogram(String name, String help, double[] bounds, String... labelNames) {
        this.name = name;
        this.help = help;
        this.bounds = bounds.clone();
        this.labelNames = labelNames.clone();
    }

    /** While disabled {@link #observe} returns at once (the default registry is off until metrics are enabled). */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Records one duration; {@code labelValues} match the label names given at construction. */
    public void observe(double seconds, String... labelValues) {
        if (!enabled) {
            return;
        }
        if (labelValues.length != labelNames.length) {
            throw new IllegalArgumentException(name + " takes " + labelNames.length + " label values");
        }
        String key = String.join("\u0000", labelValues);
        Series target = series.get(key);
        if (target == null) {
            if (series.size() >= MAX_SERIES) {
                String[] other = new String[labelNames.length];
                java.util.Arrays.fill(other, "other");
                key = String.join("\u0000", other);
                labelValues = other;
            }
            final String[] values = labelValues.clone();
            final String finalKey = key;
            target = series.computeIfAbsent(finalKey, k -> {
                labelValuesByKey.put(finalKey, values);
                return new Series(bounds.length);
            });
        }
        int index = bounds.length;
        for (int i = 0; i < bounds.length; i++) {
            if (seconds <= bounds[i]) {
                index = i;
                break;
            }
        }
        target.buckets.incrementAndGet(index);
        target.sum.add(seconds);
    }

    /** Times a task and records its duration (also when it throws). */
    public void time(Runnable task, String... labelValues) {
        long start = System.nanoTime();
        try {
            task.run();
        } finally {
            observe((System.nanoTime() - start) / 1e9, labelValues);
        }
    }

    @Override
    public void collect(PrometheusWriter out) {
        for (Map.Entry<String, Series> entry : series.entrySet()) {
            Series s = entry.getValue();
            long[] cumulative = new long[bounds.length + 1];
            long running = 0;
            for (int i = 0; i <= bounds.length; i++) {
                running += s.buckets.get(i);
                cumulative[i] = running;
            }
            String[] values = labelValuesByKey.get(entry.getKey());
            String[] pairs = new String[labelNames.length * 2];
            for (int i = 0; i < labelNames.length; i++) {
                pairs[i * 2] = labelNames[i];
                pairs[i * 2 + 1] = values[i];
            }
            out.histogram(name, help, bounds, cumulative, s.sum.sum(), pairs);
        }
    }
}

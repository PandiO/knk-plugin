package net.knightsandkings.knk.core.realtime;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Delays between reconnect attempts of the realtime client (PandiO/knk-workspace#178, DESIGN §4.4): the Java SignalR
 * client has no automatic reconnect, so after a drop the plugin waits 1 s, 2 s, 5 s, 10 s, then 30 s for every further
 * attempt, each with up to {@code jitter} (a fraction, 0.2 = ±20 %) randomness so several servers don't retry in step.
 * {@link #reset()} after a successful connect. Not thread-safe; the client calls it from its own executor.
 */
public final class ReconnectBackoff {

    public static final List<Duration> DEFAULT_STEPS = List.of(
        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30));
    public static final double DEFAULT_JITTER = 0.2;

    private final List<Duration> steps;
    private final double jitter;
    private final DoubleSupplier random;
    private int attempt;

    /**
     * @param steps  delays in order; the last one repeats (cap). Must not be empty.
     * @param jitter 0 to 1: each delay is scaled by a random factor in {@code [1 - jitter, 1 + jitter]}
     * @param random uniform values in {@code [0, 1)}
     */
    public ReconnectBackoff(List<Duration> steps, double jitter, DoubleSupplier random) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("At least one backoff step is required");
        }
        this.steps = List.copyOf(steps);
        this.jitter = Math.max(0.0, Math.min(1.0, jitter));
        this.random = Objects.requireNonNull(random, "random");
    }

    public static ReconnectBackoff defaults() {
        return new ReconnectBackoff(DEFAULT_STEPS, DEFAULT_JITTER, Math::random);
    }

    /** The delay before the next attempt; each call counts as one attempt. */
    public Duration nextDelay() {
        Duration base = steps.get(Math.min(attempt, steps.size() - 1));
        attempt++;
        double factor = 1.0 + jitter * (2.0 * random.getAsDouble() - 1.0);
        long millis = Math.max(0L, Math.round(base.toMillis() * factor));
        return Duration.ofMillis(millis);
    }

    /** Attempts counted since the last {@link #reset()}. */
    public int attempts() {
        return attempt;
    }

    public void reset() {
        attempt = 0;
    }

    public List<Duration> steps() {
        return steps;
    }
}

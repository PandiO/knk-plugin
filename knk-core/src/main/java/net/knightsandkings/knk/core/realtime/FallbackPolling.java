package net.knightsandkings.knk.core.realtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * The periodic re-syncs stay as a safety net while the realtime hub is connected, but run
 * {@code realtime.fallback-poll-multiplier} times less often (DESIGN §7.2, decision Q4: keep, 4x slower;
 * PandiO/knk-workspace#179). While the hub is down (or the channel is off) every re-sync runs at its own interval, as
 * before.
 * <p>
 * Two ways to use it: {@link #throttle(Runnable)} for a fixed-rate timer (the wrapper skips all but every n-th run
 * while connected), {@link #interval(Duration)} for a self-rescheduling poller.
 */
public final class FallbackPolling {

    public static final int DEFAULT_MULTIPLIER = 4;

    private final BooleanSupplier connected;
    private final IntSupplier multiplier;

    /**
     * @param connected  whether the hub is connected right now
     * @param multiplier how much slower while connected; values below 1 count as 1 (no slowdown)
     */
    public FallbackPolling(BooleanSupplier connected, IntSupplier multiplier) {
        this.connected = Objects.requireNonNull(connected, "connected");
        this.multiplier = Objects.requireNonNull(multiplier, "multiplier");
    }

    /** Never slows anything down (the channel is off or not wired). */
    public static FallbackPolling never() {
        return new FallbackPolling(() -> false, () -> 1);
    }

    public boolean connected() {
        return connected.getAsBoolean();
    }

    public int multiplier() {
        return Math.max(1, multiplier.getAsInt());
    }

    /** {@code base}, times the multiplier while connected. */
    public Duration interval(Duration base) {
        return connected() ? base.multipliedBy(multiplier()) : base;
    }

    /**
     * Wraps a task that a timer runs every period: while connected only every n-th run goes through (the first run
     * after connecting waits n periods); while disconnected every run does, and the count starts again.
     */
    public Runnable throttle(Runnable task) {
        Objects.requireNonNull(task, "task");
        AtomicInteger skipped = new AtomicInteger();
        return () -> {
            if (!connected()) {
                skipped.set(0);
                task.run();
                return;
            }
            if (skipped.incrementAndGet() >= multiplier()) {
                skipped.set(0);
                task.run();
            }
        };
    }
}

package net.knightsandkings.knk.core.survival.wave;

/**
 * The break between rounds (DESIGN §3 step 5, S27): configurable, default 10 s. Deaths during the break never count
 * toward a team wipe (S41); the end-condition evaluator only checks wipes while a wave is active.
 *
 * @param startMillis    when the break started
 * @param durationMillis the configured round delay
 */
public record Intermission(long startMillis, long durationMillis) {

    /** S27 default: 10 s. */
    public static final long DEFAULT_DURATION_MILLIS = 10_000L;

    public Intermission {
        if (durationMillis < 0) throw new IllegalArgumentException("durationMillis must be >= 0");
    }

    public static Intermission startingAt(long nowMillis, long durationMillis) {
        return new Intermission(nowMillis, durationMillis);
    }

    public long endMillis() {
        return startMillis + durationMillis;
    }

    public long remainingMillis(long nowMillis) {
        return Math.max(0, endMillis() - nowMillis);
    }

    /** Whole seconds left, rounded up, for the countdown display. */
    public int remainingSecondsCeil(long nowMillis) {
        return (int) ((remainingMillis(nowMillis) + 999) / 1000);
    }

    public boolean isOver(long nowMillis) {
        return nowMillis >= endMillis();
    }
}

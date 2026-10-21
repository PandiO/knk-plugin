package net.knightsandkings.knk.core.duel;

/**
 * The duel rules count time in server ticks (the runtime passes {@code Bukkit.getCurrentTick()}), so
 * two events "on the same tick" can be told apart from events a tick apart (DESIGN §8.6:
 * simultaneous death).
 */
public final class DuelTicks {
    public static final int PER_SECOND = 20;

    private DuelTicks() {}

    public static long ofSeconds(long seconds) {
        return seconds * PER_SECOND;
    }

    /** Whole seconds left until {@code deadlineTick}, rounded up; never negative. */
    public static int secondsUntil(long deadlineTick, long nowTick) {
        long left = deadlineTick - nowTick;
        if (left <= 0) return 0;
        return (int) ((left + PER_SECOND - 1) / PER_SECOND);
    }
}

package net.knightsandkings.knk.core.survival.stuck;

import java.util.OptionalLong;

/**
 * Stuck-enemy handling settings (DESIGN §3c, S43); the defaults are the design's configurable starting values.
 *
 * @param stuckAfterMillis       N: no progress for this long = stuck (default 30 s)
 * @param lastEnemiesCount       K: with the queue exhausted and at most K alive they glow and pursue directly (default 3)
 * @param respawnLimit           R: an enemy respawned R times and stuck again is system-killed (default 2)
 * @param roundBackstopMillis    T: system-kill the rest this long after the last planned spawn (default 5 min);
 *                               empty = off
 * @param meaningfulDistance     blocks an enemy must close toward its target to count as progress (default 1.0;
 *                               an implementation choice, the design says "meaningful reduction")
 */
public record StuckSettings(long stuckAfterMillis, int lastEnemiesCount, int respawnLimit, OptionalLong roundBackstopMillis,
                            double meaningfulDistance) {

    public static final long DEFAULT_STUCK_AFTER_MILLIS = 30_000L;
    public static final int DEFAULT_LAST_ENEMIES_COUNT = 3;
    public static final int DEFAULT_RESPAWN_LIMIT = 2;
    public static final long DEFAULT_ROUND_BACKSTOP_MILLIS = 5 * 60_000L;
    public static final double DEFAULT_MEANINGFUL_DISTANCE = 1.0;

    public StuckSettings {
        if (stuckAfterMillis <= 0) throw new IllegalArgumentException("stuckAfterMillis must be > 0");
        if (lastEnemiesCount < 0) throw new IllegalArgumentException("lastEnemiesCount must be >= 0");
        if (respawnLimit < 0) throw new IllegalArgumentException("respawnLimit must be >= 0");
        if (roundBackstopMillis == null) throw new IllegalArgumentException("roundBackstopMillis is required (empty = off)");
        if (roundBackstopMillis.isPresent() && roundBackstopMillis.getAsLong() <= 0) {
            throw new IllegalArgumentException("roundBackstopMillis must be > 0 or empty (off)");
        }
        if (!(meaningfulDistance > 0)) throw new IllegalArgumentException("meaningfulDistance must be > 0");
    }

    public static StuckSettings defaults() {
        return new StuckSettings(DEFAULT_STUCK_AFTER_MILLIS, DEFAULT_LAST_ENEMIES_COUNT, DEFAULT_RESPAWN_LIMIT,
            OptionalLong.of(DEFAULT_ROUND_BACKSTOP_MILLIS), DEFAULT_MEANINGFUL_DISTANCE);
    }

    public StuckSettings withoutBackstop() {
        return new StuckSettings(stuckAfterMillis, lastEnemiesCount, respawnLimit, OptionalLong.empty(), meaningfulDistance);
    }

    public StuckSettings withBackstop(long millis) {
        return new StuckSettings(stuckAfterMillis, lastEnemiesCount, respawnLimit, OptionalLong.of(millis), meaningfulDistance);
    }

    public boolean backstopEnabled() {
        return roundBackstopMillis.isPresent();
    }
}

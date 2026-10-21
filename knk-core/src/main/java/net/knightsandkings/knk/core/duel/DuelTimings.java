package net.knightsandkings.knk.core.duel;

/**
 * The timings one match runs with, frozen at lock-in (DESIGN §4.2, §12).
 *
 * @param fightTimeLimitSeconds    0 = no limit (D11)
 * @param agreedDrawWindowSeconds  config {@code duels.agreed-draw-window-seconds} (15): the second
 *                                 {@code /duel draw} must come within this many seconds of the first
 */
public record DuelTimings(
        int warmupSeconds,
        int countdownSeconds,
        int fightTimeLimitSeconds,
        int winnerLingerSeconds,
        int agreedDrawWindowSeconds
) {
    public static final int DEFAULT_AGREED_DRAW_WINDOW_SECONDS = 15;

    public DuelTimings {
        if (warmupSeconds < 0 || countdownSeconds < 0 || fightTimeLimitSeconds < 0 || winnerLingerSeconds < 0
                || agreedDrawWindowSeconds < 0) {
            throw new IllegalArgumentException("timings must be >= 0");
        }
    }

    public boolean hasFightTimeLimit() {
        return fightTimeLimitSeconds > 0;
    }
}

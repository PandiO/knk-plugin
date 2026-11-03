package net.knightsandkings.knk.core.survival.economy;

import java.math.BigDecimal;

/**
 * Per-kill reward settings (S32, S33, §9): permanent coins and XP per NPC kill (default the Siege scenario's
 * capture reward ÷ 10: dev seed 30 / 3), match currency per kill (default 70) and the headshot/critical multiplier
 * (default ×1.1, applied once even when a kill is both).
 *
 * @param coinsPerKill         permanent coins per kill
 * @param xpPerKill            permanent experience per kill
 * @param matchCurrencyPerKill match currency per kill
 * @param bonusMultiplier      headshot / critical-hit multiplier
 */
public record KillRewardSettings(BigDecimal coinsPerKill, BigDecimal xpPerKill, long matchCurrencyPerKill, BigDecimal bonusMultiplier) {

    public static final BigDecimal DEFAULT_COINS = new BigDecimal("30");
    public static final BigDecimal DEFAULT_XP = new BigDecimal("3");
    public static final long DEFAULT_MATCH_CURRENCY = 70;
    public static final BigDecimal DEFAULT_BONUS_MULTIPLIER = new BigDecimal("1.1");

    public KillRewardSettings {
        if (coinsPerKill == null || coinsPerKill.signum() < 0) throw new IllegalArgumentException("coinsPerKill must be >= 0");
        if (xpPerKill == null || xpPerKill.signum() < 0) throw new IllegalArgumentException("xpPerKill must be >= 0");
        if (matchCurrencyPerKill < 0) throw new IllegalArgumentException("matchCurrencyPerKill must be >= 0");
        if (bonusMultiplier == null || bonusMultiplier.signum() <= 0) throw new IllegalArgumentException("bonusMultiplier must be > 0");
    }

    /** The dev-seed defaults: 30 coins, 3 XP, 70 match currency, ×1.1. */
    public static KillRewardSettings defaults() {
        return new KillRewardSettings(DEFAULT_COINS, DEFAULT_XP, DEFAULT_MATCH_CURRENCY, DEFAULT_BONUS_MULTIPLIER);
    }

    /** Derived from a Siege scenario's capture rewards ÷ 10 (S32), keeping the other defaults. */
    public static KillRewardSettings fromCaptureRewards(long coinRewardCapture, long expRewardCapture) {
        return new KillRewardSettings(BigDecimal.valueOf(coinRewardCapture).divide(BigDecimal.TEN),
            BigDecimal.valueOf(expRewardCapture).divide(BigDecimal.TEN), DEFAULT_MATCH_CURRENCY, DEFAULT_BONUS_MULTIPLIER);
    }
}

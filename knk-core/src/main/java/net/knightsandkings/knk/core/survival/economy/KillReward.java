package net.knightsandkings.knk.core.survival.economy;

import java.math.BigDecimal;

/**
 * What one kill pays (S32, S33): permanent coins and XP as exact values (tenths survive; rounded down once at
 * settlement) and match currency credited immediately as a whole amount.
 */
public record KillReward(BigDecimal coins, BigDecimal xp, long matchCurrency) {

    public static final KillReward NONE = new KillReward(BigDecimal.ZERO, BigDecimal.ZERO, 0);

    public KillReward {
        if (coins == null || xp == null) throw new IllegalArgumentException("amounts are required");
        if (coins.signum() < 0 || xp.signum() < 0 || matchCurrency < 0) throw new IllegalArgumentException("amounts must be >= 0");
    }

    public boolean isNone() {
        return coins.signum() == 0 && xp.signum() == 0 && matchCurrency == 0;
    }
}

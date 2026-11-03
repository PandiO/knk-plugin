package net.knightsandkings.knk.core.survival.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Per-kill reward (S32, S33, S58, §8, §9): a living player's killing blow pays the configured coins, XP and match
 * currency, ×{@code bonusMultiplier} once for a headshot or critical kill. System (stuck handling), environment and
 * NPC kills pay nothing; a ghost earns nothing. Match currency is a whole amount, rounded down per kill (70 × 1.1 =
 * 77 exactly with the defaults); coins and XP keep their exact value for the single rounding at settlement.
 */
public final class KillRewardCalculator {
    private final KillRewardSettings settings;

    public KillRewardCalculator(KillRewardSettings settings) {
        this.settings = settings;
    }

    public KillRewardSettings settings() {
        return settings;
    }

    public KillReward reward(EnemyKill kill) {
        if (!kill.cause().earned() || kill.killer().isEmpty() || kill.killerIsGhost()) {
            return KillReward.NONE;
        }
        BigDecimal multiplier = kill.bonus() ? settings.bonusMultiplier() : BigDecimal.ONE;
        BigDecimal coins = settings.coinsPerKill().multiply(multiplier);
        BigDecimal xp = settings.xpPerKill().multiply(multiplier);
        long currency = BigDecimal.valueOf(settings.matchCurrencyPerKill()).multiply(multiplier)
            .setScale(0, RoundingMode.FLOOR).longValueExact();
        return new KillReward(coins, xp, currency);
    }
}

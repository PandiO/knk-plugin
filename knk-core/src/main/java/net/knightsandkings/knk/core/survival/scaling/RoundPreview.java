package net.knightsandkings.knk.core.survival.scaling;

import java.util.Map;

/**
 * What the editor shows per round (DESIGN §7 "preview the resulting count and stat multiplier per round", §11).
 *
 * @param round           the round
 * @param players         the player count the preview assumed
 * @param enemyCount      enemies in the round (after per-player scaling, rounding and the cap)
 * @param statMultipliers multiplier per stat (after weight, optional per-player scaling and the cap)
 */
public record RoundPreview(int round, int players, int enemyCount, Map<ScaledStat, Double> statMultipliers) {

    public RoundPreview {
        statMultipliers = Map.copyOf(statMultipliers);
    }

    public double multiplier(ScaledStat stat) {
        return statMultipliers.getOrDefault(stat, 1.0);
    }
}

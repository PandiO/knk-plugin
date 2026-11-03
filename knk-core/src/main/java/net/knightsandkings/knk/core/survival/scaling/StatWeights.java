package net.knightsandkings.knk.core.survival.scaling;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-stat weight w on the shared stat curve (S54): {@code 1 + w·(curve(r) − 1)}. w = 1 follows the curve fully,
 * w = 0.3 grows at 30 % of it, w = 0 freezes the stat at vanilla. Missing stats default to 1.
 */
public record StatWeights(Map<ScaledStat, Double> weights) {

    public static final double DEFAULT_WEIGHT = 1.0;

    public StatWeights {
        Map<ScaledStat, Double> copy = new EnumMap<>(ScaledStat.class);
        for (Map.Entry<ScaledStat, Double> e : weights.entrySet()) {
            if (e.getValue() == null || !Double.isFinite(e.getValue()) || e.getValue() < 0) {
                throw new IllegalArgumentException("weight of " + e.getKey() + " must be >= 0 and finite");
            }
            copy.put(e.getKey(), e.getValue());
        }
        weights = Map.copyOf(copy);
    }

    /** Every stat follows the curve fully. */
    public static StatWeights uniform() {
        return new StatWeights(Map.of());
    }

    public double weight(ScaledStat stat) {
        return weights.getOrDefault(stat, DEFAULT_WEIGHT);
    }

    public StatWeights with(ScaledStat stat, double weight) {
        Map<ScaledStat, Double> copy = new EnumMap<>(ScaledStat.class);
        copy.putAll(weights);
        copy.put(stat, weight);
        return new StatWeights(copy);
    }
}

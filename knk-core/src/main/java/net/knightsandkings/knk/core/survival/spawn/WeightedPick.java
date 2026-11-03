package net.knightsandkings.knk.core.survival.spawn;

import java.util.List;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/**
 * Weighted random choice with double weights (a local copy of the idea behind {@code core/siege/WeightedPicker},
 * which is package-private and integer-weighted; shared files are not changed by this link). Items with a weight of
 * 0 or less never come out.
 */
final class WeightedPick {
    private WeightedPick() { }

    static <T> Optional<T> pick(List<T> items, ToDoubleFunction<T> weight, RandomGenerator random) {
        double total = 0;
        for (T item : items) total += Math.max(0, weight.applyAsDouble(item));
        if (!(total > 0)) return Optional.empty();
        double roll = random.nextDouble() * total;
        T last = null;
        for (T item : items) {
            double w = Math.max(0, weight.applyAsDouble(item));
            if (w <= 0) continue;
            last = item;
            roll -= w;
            if (roll < 0) return Optional.of(item);
        }
        return Optional.ofNullable(last);
    }
}

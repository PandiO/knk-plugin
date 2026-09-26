package net.knightsandkings.knk.core.siege;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;
import java.util.random.RandomGenerator;

/** Weighted random choice for rotation draws; weights below 1 count as 1. */
final class WeightedPicker {
    private WeightedPicker() {}

    static <T> Optional<T> pick(List<T> items, ToIntFunction<T> weight, RandomGenerator random) {
        if (items.isEmpty()) return Optional.empty();
        long total = 0;
        for (T item : items) total += Math.max(1, weight.applyAsInt(item));
        long roll = random.nextLong(total);
        for (T item : items) {
            roll -= Math.max(1, weight.applyAsInt(item));
            if (roll < 0) return Optional.of(item);
        }
        return Optional.of(items.get(items.size() - 1));
    }

    /** Up to {@code count} distinct items, each drawn by weight from those not yet drawn. */
    static <T> List<T> pickDistinct(List<T> items, int count, ToIntFunction<T> weight, RandomGenerator random) {
        List<T> remaining = new ArrayList<>(items);
        List<T> picked = new ArrayList<>();
        while (picked.size() < count && !remaining.isEmpty()) {
            T next = pick(remaining, weight, random).orElseThrow();
            picked.add(next);
            remaining.remove(next);
        }
        return List.copyOf(picked);
    }

    static <T> Optional<T> pickUniform(List<T> items, RandomGenerator random) {
        return items.isEmpty() ? Optional.empty() : Optional.of(items.get(random.nextInt(items.size())));
    }
}

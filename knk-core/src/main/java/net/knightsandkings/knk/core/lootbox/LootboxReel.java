package net.knightsandkings.knk.core.lootbox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * The opening animation's reel (docs/specs/lootboxes/DESIGN.md §3.9): a row of items that scrolls past a marker,
 * slows down and stops on the item the API already rolled. Bukkit-free, so the plan is unit-tested; the menu just
 * shows {@link #window(int)} after each {@link #delays()} step.
 * <p>
 * The roll happens before the reel is built and is never influenced by it. The passing items are drawn with the box's
 * real odds (the odds preview), so the reel shows what the box can give as often as it gives it: no rigged near-misses
 * next to the winner.
 *
 * @param items   the whole strip, left to right; the window of {@code visible} slots moves one step at a time
 * @param visible how many slots the row shows (9 in a chest menu)
 * @param delays  ticks to wait before each step; the last step leaves the winner under the marker
 */
public record LootboxReel<T>(List<T> items, int visible, List<Integer> delays) {

    /** A reel candidate with its chance (any positive scale, e.g. a percentage). */
    public record Weighted<T>(T value, double weight) {
    }

    public LootboxReel {
        items = List.copyOf(items);
        delays = List.copyOf(delays);
    }

    /** The slot of the window under the marker (the middle one). */
    public int markerOffset() {
        return visible / 2;
    }

    /** How many steps the reel scrolls. */
    public int steps() {
        return delays.size();
    }

    /** What the row shows after {@code step} steps (0 = the start). */
    public List<T> window(int step) {
        int from = Math.max(0, Math.min(step, items.size() - visible));
        return items.subList(from, from + visible);
    }

    /** The item under the marker after {@code step} steps. */
    public T atMarker(int step) {
        return window(step).get(markerOffset());
    }

    /**
     * Plans a reel of {@code steps} steps that ends with {@code winner} under the marker.
     *
     * @param candidates   what may pass by, weighted by its chance; empty = only the winner is shown
     * @param winner       the rolled item
     * @param steps        scroll steps (at least 1)
     * @param visible      slots in the row (odd, at least 1)
     * @param maxStepTicks the slowest step at the end; the first step takes 1 tick, then it eases out
     * @param random       0 (inclusive) - 1 (exclusive)
     */
    public static <T> LootboxReel<T> plan(List<Weighted<T>> candidates, T winner, int steps, int visible, int maxStepTicks,
                                          DoubleSupplier random) {
        if (winner == null) {
            throw new IllegalArgumentException("winner is required");
        }
        int stepCount = Math.max(1, steps);
        int slots = Math.max(1, visible);
        int length = stepCount + slots;
        int winnerIndex = stepCount + slots / 2; // under the marker after the last step

        List<Weighted<T>> usable = candidates == null ? List.of()
                : candidates.stream().filter(c -> c != null && c.value() != null && c.weight() > 0).toList();
        double total = usable.stream().mapToDouble(Weighted::weight).sum();

        List<T> strip = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            strip.add(i == winnerIndex ? winner : draw(usable, total, winner, random));
        }
        return new LootboxReel<>(strip, slots, easeOut(stepCount, Math.max(1, maxStepTicks)));
    }

    private static <T> T draw(List<Weighted<T>> candidates, double total, T fallback, DoubleSupplier random) {
        if (candidates.isEmpty() || total <= 0) {
            return fallback;
        }
        double pick = random.getAsDouble() * total;
        for (Weighted<T> candidate : candidates) {
            pick -= candidate.weight();
            if (pick < 0) {
                return candidate.value();
            }
        }
        return candidates.get(candidates.size() - 1).value();
    }

    /** 1 tick per step at the start, growing quadratically to {@code maxTicks} for the last step. */
    static List<Integer> easeOut(int steps, int maxTicks) {
        List<Integer> delays = new ArrayList<>(steps);
        for (int i = 0; i < steps; i++) {
            double progress = steps == 1 ? 1.0 : (double) i / (steps - 1);
            delays.add(1 + (int) Math.round((maxTicks - 1) * progress * progress));
        }
        return delays;
    }

    /** Total ticks from the first step to the stop. */
    public int totalTicks() {
        return delays.stream().mapToInt(Integer::intValue).sum();
    }
}

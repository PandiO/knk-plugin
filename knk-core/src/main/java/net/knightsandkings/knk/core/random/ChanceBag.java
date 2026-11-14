package net.knightsandkings.knk.core.random;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * One chance as a {@link MarbleBag} of hits and misses (PandiO/knk-workspace#476): over time it hits as often as an
 * independent roll would, but every bag holds its share of hits, so long runs of bad or good luck can't happen.
 * <p>
 * The bag is the smallest size within {@link Size} in which the chance is a whole number of hits (25% in a bag of
 * 8 = 2 hits, 15% in a bag of 20 = 3). A chance that fits no such size (33%, or 1% in a bag of 20) uses the largest
 * bag and carries the fraction of a hit over to the next one, so the average stays exact: 1% in bags of 20 is one hit
 * in every fifth bag. The carry starts at a random point, so the first bags are not short of hits.
 */
public final class ChanceBag {
    private static final double WHOLE_HITS_TOLERANCE = 1e-9;

    private final double chance;
    private final int bagSize;
    private final MarbleBag<Boolean> bag;
    private double carry;

    /**
     * @param chance 0-1; below 0 counts as 0 (never), above 1 as 1 (always), NaN as 0
     */
    public ChanceBag(double chance, Size size, RandomGenerator random) {
        Objects.requireNonNull(size, "size must not be null");
        Objects.requireNonNull(random, "random must not be null");
        this.chance = Double.isNaN(chance) ? 0.0d : Math.max(0.0d, Math.min(1.0d, chance));
        this.bagSize = size.bagSizeFor(this.chance);
        this.carry = random.nextDouble();
        this.bag = MarbleBag.refilledBy(this::fill, random);
    }

    /** Draws the next outcome: {@code true} for a hit. */
    public boolean next() {
        return bag.next();
    }

    /** The chance this bag was made for, 0-1. */
    public double chance() {
        return chance;
    }

    /** How many draws one bag holds. */
    public int bagSize() {
        return bagSize;
    }

    private List<Boolean> fill() {
        double exact = chance * bagSize + carry;
        int hits = Math.min(bagSize, (int) Math.floor(exact));
        carry = exact - hits;
        List<Boolean> marbles = new ArrayList<>(bagSize);
        for (int i = 0; i < bagSize; i++) {
            marbles.add(i < hits);
        }
        return marbles;
    }

    /**
     * The smallest and largest bag a chance may get. A larger bag leaves more room for streaks (and is harder to
     * count along with); a smaller one is stricter. {@code 1, 1} would make every draw an independent roll.
     *
     * @param min smallest bag, at least 1
     * @param max largest bag, at least {@code min}; used when the chance fits no smaller bag in whole hits
     */
    public record Size(int min, int max) {
        /** 8-20: 25% is two hits in eight draws, 5% one in twenty. */
        public static final Size DEFAULT = new Size(8, 20);

        public Size {
            min = Math.max(1, min);
            max = Math.max(min, max);
        }

        int bagSizeFor(double chance) {
            for (int size = min; size < max; size++) {
                double hits = chance * size;
                if (hits >= 1.0d - WHOLE_HITS_TOLERANCE && Math.abs(hits - Math.rint(hits)) < WHOLE_HITS_TOLERANCE) {
                    return size;
                }
            }
            return max;
        }
    }
}

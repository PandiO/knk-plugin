package net.knightsandkings.knk.core.survival.mysterybox;

/**
 * Percentages as integer basis points (1 % = 100 bp, 100 % = {@link #WHOLE}), so "adds up to exactly 100 %"
 * (S59) is an exact integer comparison instead of a double tolerance. Percent inputs are accepted to 0.01 %
 * precision; anything finer is rejected so a pool cannot silently drift from 100.
 */
public final class BasisPoints {
    public static final int WHOLE = 10_000;
    private static final double EPSILON = 1e-6;

    private BasisPoints() { }

    /** @throws IllegalArgumentException when the percentage is not a multiple of 0.01 or outside 0..100 */
    public static int fromPercent(double percent) {
        if (!Double.isFinite(percent) || percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent must be within 0..100: " + percent);
        }
        double scaled = percent * 100;
        long rounded = Math.round(scaled);
        if (Math.abs(scaled - rounded) > EPSILON) {
            throw new IllegalArgumentException("percent must be a multiple of 0.01: " + percent);
        }
        return (int) rounded;
    }

    public static double toPercent(int basisPoints) {
        return basisPoints / 100.0;
    }
}

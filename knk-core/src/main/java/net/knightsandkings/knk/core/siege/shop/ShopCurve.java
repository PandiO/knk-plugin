package net.knightsandkings.knk.core.siege.shop;

import java.util.Locale;

/**
 * How a shop entry's price or cooldown step grows with the purchase number {@code i} (1, 2, 3, ...) (siege shop
 * DESIGN § 5.4, P06; PandiO/knk-workspace#868). The API sends the names {@code None}, {@code Linear},
 * {@code Logarithmic} and {@code Exponential}.
 */
public enum ShopCurve {
    /** {@code s}: the same step every time. */
    NONE,
    /** {@code s × i}. */
    LINEAR,
    /** {@code s × (1 + ln i)}. */
    LOGARITHMIC,
    /** {@code s × f^(i − 1)}, growth factor {@code f} (default {@link #DEFAULT_GROWTH_FACTOR}). */
    EXPONENTIAL;

    /** The growth factor of an exponential curve when the entry has none (§ 5.4). */
    public static final double DEFAULT_GROWTH_FACTOR = 1.5;

    /**
     * The step for purchase number {@code i}.
     *
     * @param step   the entry's step {@code s}
     * @param i      the purchase number, from 1
     * @param factor the growth factor; null or not above 1 means {@link #DEFAULT_GROWTH_FACTOR}
     */
    public double step(double step, int i, Double factor) {
        int n = Math.max(1, i);
        return switch (this) {
            case NONE -> step;
            case LINEAR -> step * n;
            case LOGARITHMIC -> step * (1 + Math.log(n));
            case EXPONENTIAL -> step * Math.pow(factor == null || !(factor > 1) ? DEFAULT_GROWTH_FACTOR : factor, n - 1);
        };
    }

    /** The API's name ({@code "Exponential"}); null or unknown is {@link #NONE}. */
    public static ShopCurve fromApi(String value) {
        if (value == null || value.isBlank()) return NONE;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}

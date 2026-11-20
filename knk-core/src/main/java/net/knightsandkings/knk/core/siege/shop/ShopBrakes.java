package net.knightsandkings.knk.core.siege.shop;

/**
 * The three brakes of a shop entry (siege shop DESIGN § 5.4, P06; PandiO/knk-workspace#868), for one player, one
 * entry, one match. {@code n} is the number of purchases of the entry so far (0 before the first).
 * <ul>
 *   <li><b>Price:</b> {@code price(n) = base + Σ_{i=1..n} step_i}, at most the maximum price, rounded to whole points;
 *       no price step = a fixed price.</li>
 *   <li><b>Cooldown:</b> after purchase number {@code k}, {@code cooldown(k) = base + Σ_{i=1..k−1} step_i} seconds, at
 *       most the maximum; no base cooldown = none.</li>
 *   <li><b>Cap:</b> at most {@code MaxPerMatch} purchases; none = no cap.</li>
 * </ul>
 * Example (§ 5.4.5): ender pearl, base 300, step 50 linear: 300, 350, 450, 600, 800; cooldown 20 s, step 10 s
 * exponential ×1.5: 20, 30, 45, 67.5 s. Pure.
 */
public final class ShopBrakes {

    private ShopBrakes() {
    }

    /** The price of the next purchase after {@code purchasesSoFar} purchases. */
    public static long price(ShopEntry entry, int purchasesSoFar) {
        double price = entry.price();
        if (entry.priceStep() != null && entry.priceStep() > 0) {
            for (int i = 1; i <= purchasesSoFar; i++) {
                price += entry.priceCurve().step(entry.priceStep(), i, entry.priceGrowthFactor());
                if (entry.priceMax() != null && price >= entry.priceMax()) break;
            }
        }
        if (entry.priceMax() != null && entry.priceMax() >= 0) price = Math.min(price, entry.priceMax());
        return Math.max(0, Math.round(price));
    }

    /** Seconds the entry is locked after purchase number {@code purchaseNumber} (1 for the first); 0 without a cooldown. */
    public static double cooldownSeconds(ShopEntry entry, int purchaseNumber) {
        if (entry.cooldownSeconds() == null || !(entry.cooldownSeconds() > 0)) return 0;
        double seconds = entry.cooldownSeconds();
        if (entry.cooldownStepSeconds() != null && entry.cooldownStepSeconds() > 0) {
            for (int i = 1; i <= purchaseNumber - 1; i++) {
                seconds += entry.cooldownCurve().step(entry.cooldownStepSeconds(), i, entry.cooldownGrowthFactor());
                if (entry.cooldownMaxSeconds() != null && seconds >= entry.cooldownMaxSeconds()) break;
            }
        }
        if (entry.cooldownMaxSeconds() != null && entry.cooldownMaxSeconds() >= 0) seconds = Math.min(seconds, entry.cooldownMaxSeconds());
        return Math.max(0, seconds);
    }

    /** {@link #cooldownSeconds} in milliseconds. */
    public static long cooldownMillis(ShopEntry entry, int purchaseNumber) {
        return Math.round(cooldownSeconds(entry, purchaseNumber) * 1000.0);
    }

    /** True when {@code purchasesSoFar} already reached the entry's cap. */
    public static boolean capReached(ShopEntry entry, int purchasesSoFar) {
        return entry.capped() && purchasesSoFar >= entry.maxPerMatch();
    }
}

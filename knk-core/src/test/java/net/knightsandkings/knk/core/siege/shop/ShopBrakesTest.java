package net.knightsandkings.knk.core.siege.shop;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The brakes of siege shop DESIGN § 5.4 (P06; A20, A21, A22; PandiO/knk-workspace#868). */
class ShopBrakesTest {

    private static long[] prices(ShopEntry e, int count) {
        return IntStream.range(0, count).mapToLong(n -> ShopBrakes.price(e, n)).toArray();
    }

    private static double[] cooldowns(ShopEntry e, int count) {
        return IntStream.rangeClosed(1, count).mapToDouble(k -> ShopBrakes.cooldownSeconds(e, k)).toArray();
    }

    @Test
    void theDesignExample_pearlPricesAndCooldowns_A20_A21() {
        ShopEntry pearl = ShopFixtures.pearl();
        assertArrayEquals(new long[] {300, 350, 450, 600, 800}, prices(pearl, 5));
        assertArrayEquals(new double[] {20, 30, 45, 67.5}, cooldowns(pearl, 4), 1e-9);
    }

    @Test
    void priceGrowsPerCurve_A20() {
        ShopEntry base = ShopFixtures.arrows();
        // None: 100 + 10 per purchase.
        assertArrayEquals(new long[] {100, 110, 120, 130}, prices(ShopFixtures.withPrice(base, 100, 10L, ShopCurve.NONE, null, null), 4));
        // Linear: steps 10, 20, 30.
        assertArrayEquals(new long[] {100, 110, 130, 160}, prices(ShopFixtures.withPrice(base, 100, 10L, ShopCurve.LINEAR, null, null), 4));
        // Logarithmic: steps 10, 10(1+ln2)=16.93, 10(1+ln3)=20.99 -> 100, 110, 126.93, 147.92 rounded.
        assertArrayEquals(new long[] {100, 110, 127, 148}, prices(ShopFixtures.withPrice(base, 100, 10L, ShopCurve.LOGARITHMIC, null, null), 4));
        // Exponential ×2: steps 10, 20, 40.
        assertArrayEquals(new long[] {100, 110, 130, 170}, prices(ShopFixtures.withPrice(base, 100, 10L, ShopCurve.EXPONENTIAL, 2.0, null), 4));
        // Exponential without a factor: 1.5 (§ 5.4): steps 10, 15, 22.5.
        assertArrayEquals(new long[] {100, 110, 125, 148}, prices(ShopFixtures.withPrice(base, 100, 10L, ShopCurve.EXPONENTIAL, null, null), 4));
    }

    @Test
    void theMaximumPriceHolds_andNoStepIsAFixedPrice_A20() {
        ShopEntry capped = ShopFixtures.withPrice(ShopFixtures.arrows(), 300, 50L, ShopCurve.LINEAR, null, 500L);
        assertArrayEquals(new long[] {300, 350, 450, 500, 500, 500}, prices(capped, 6));
        assertArrayEquals(new long[] {40, 40, 40}, prices(ShopFixtures.arrows(), 3));
    }

    @Test
    void cooldownGrowsPerCurve_andTheMaximumHolds_A21() {
        ShopEntry base = ShopFixtures.arrows();
        assertArrayEquals(new double[] {10, 15, 20}, cooldowns(ShopFixtures.withCooldown(base, 10.0, 5.0, ShopCurve.NONE, null, null), 3), 1e-9);
        assertArrayEquals(new double[] {10, 15, 25}, cooldowns(ShopFixtures.withCooldown(base, 10.0, 5.0, ShopCurve.LINEAR, null, null), 3), 1e-9);
        assertArrayEquals(new double[] {10, 15, 15 + 5 * (1 + Math.log(2))},
                cooldowns(ShopFixtures.withCooldown(base, 10.0, 5.0, ShopCurve.LOGARITHMIC, null, null), 3), 1e-9);
        assertArrayEquals(new double[] {10, 15, 30}, cooldowns(ShopFixtures.withCooldown(base, 10.0, 5.0, ShopCurve.EXPONENTIAL, 3.0, null), 3), 1e-9);
        assertArrayEquals(new double[] {20, 30, 40, 40}, cooldowns(ShopFixtures.withCooldown(base, 20.0, 10.0, ShopCurve.NONE, null, 40.0), 4), 1e-9);
        assertArrayEquals(new double[] {12, 12, 12}, cooldowns(ShopFixtures.withCooldown(base, 12.0, null, ShopCurve.LINEAR, null, null), 3), 1e-9);
        assertEquals(0, ShopBrakes.cooldownMillis(base, 1), "no base cooldown = none");
        assertEquals(67_500, ShopBrakes.cooldownMillis(ShopFixtures.pearl(), 4));
    }

    @Test
    void theCapCountsPurchasesPerMatch_A22() {
        ShopEntry pearl = ShopFixtures.pearl();
        assertFalse(ShopBrakes.capReached(pearl, 7));
        assertTrue(ShopBrakes.capReached(pearl, 8));
        assertFalse(ShopBrakes.capReached(ShopFixtures.arrows(), 1000), "no cap");
    }

    @Test
    void curvesComeFromTheApiNames() {
        assertEquals(ShopCurve.EXPONENTIAL, ShopCurve.fromApi("Exponential"));
        assertEquals(ShopCurve.LOGARITHMIC, ShopCurve.fromApi("logarithmic"));
        assertEquals(ShopCurve.NONE, ShopCurve.fromApi(null));
        assertEquals(ShopCurve.NONE, ShopCurve.fromApi("Wobbly"));
        assertEquals(ShopEntryKind.SIEGE_WEAPON, ShopEntryKind.fromApi("SiegeWeapon").orElseThrow());
        assertTrue(ShopEntryKind.fromApi("MagicAction").isEmpty());
    }
}

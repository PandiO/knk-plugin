package net.knightsandkings.knk.core.siege.economy;

import net.knightsandkings.knk.core.siege.economy.SiegePointsEconomy.Credit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The action bar texts of siege shop § 4.1.1 (A10). */
class SiegePointsTextTest {

    private static final UUID A1 = new UUID(0, 1);

    @Test
    void actionBarShowsTheAmountTheSourceTheMultiplierAndTheNewBalance() {
        assertEquals("+100 kill · 100", SiegePointsText.actionBar(List.of(new Credit(A1, SiegePointsSource.KILL, 100, 1.0, 100))));
        assertEquals("+130 kill ×1.3 · 830", SiegePointsText.actionBar(List.of(new Credit(A1, SiegePointsSource.KILL, 130, 1.3, 830))));
        assertEquals("+15 death · 15", SiegePointsText.actionBar(List.of(new Credit(A1, SiegePointsSource.DEATH, 15, 1.0, 15))));
        assertEquals("+3 objective +100 capture · 533", SiegePointsText.actionBar(List.of(
                new Credit(A1, SiegePointsSource.CAPTURE_PROGRESS, 3, 1.0, 433),
                new Credit(A1, SiegePointsSource.CAPTURE, 100, 1.0, 533))));
        assertEquals("", SiegePointsText.actionBar(List.of()));
    }

    @Test
    void multipliersHaveAtMostTwoDecimals() {
        assertEquals("1.5", SiegePointsText.multiplier(1.5));
        assertEquals("1.25", SiegePointsText.multiplier(1.25));
        assertEquals("2", SiegePointsText.multiplier(2.0));
        assertEquals("1.1", SiegePointsText.multiplier(1.1000000000000001));
    }
}

package net.knightsandkings.knk.core.teleport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeleportRequestSettingsTest {

    @Test
    void defaultsAreTheDesignValues() {
        TeleportRequestSettings defaults = TeleportRequestSettings.defaults();

        assertEquals(30, defaults.expireSeconds());
        assertEquals(10, defaults.cooldownSeconds());
        assertEquals(5, defaults.maxIncoming());
        assertEquals(0, defaults.priceCoins());
        assertFalse(defaults.isPaid());
    }

    @Test
    void outOfRangeValuesAreClamped() {
        TeleportRequestSettings settings = new TeleportRequestSettings(0, -1, 0, -10);

        assertEquals(1, settings.expireSeconds());
        assertEquals(0, settings.cooldownSeconds());
        assertEquals(1, settings.maxIncoming());
        assertEquals(0, settings.priceCoins());
    }

    @Test
    void anyPositivePriceMakesRequestsPaid() {
        assertTrue(new TeleportRequestSettings(30, 10, 5, 1).isPaid());
    }

    @Test
    void engineSettingsWithoutARequestBlockUseTheRequestDefaults() {
        assertEquals(TeleportRequestSettings.defaults(), new TeleportSettings(5, 3, 30, 10, 3).request());
        assertEquals(TeleportRequestSettings.defaults(), new TeleportSettings(5, 3, 30, 10, 3, null).request());
        assertEquals(TeleportRequestSettings.defaults(), TeleportSettings.defaults().request());
    }
}

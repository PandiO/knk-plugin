package net.knightsandkings.knk.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.settings.KnkWeather;
import net.knightsandkings.knk.core.domain.settings.KnkWeatherSettings;
import net.knightsandkings.knk.core.domain.settings.KnkWeatherSettings.Mode;

class WeatherCommandNoticeTest {

    private static final KnkWeatherSettings CONSTANT_RAIN = new KnkWeatherSettings(Mode.CONSTANT, KnkWeather.RAIN, Set.of(), 34, 33, 33);
    private static final KnkWeatherSettings BLOCKED_THUNDER = new KnkWeatherSettings(Mode.BLOCKED, null, Set.of(KnkWeather.THUNDER), 34, 33, 33);
    private static final KnkWeatherSettings WEIGHTED_THUNDER = new KnkWeatherSettings(Mode.WEIGHTED, null, Set.of(), 0, 0, 100);

    @Test
    void requestedWeather() {
        assertEquals(KnkWeather.CLEAR, WeatherCommandNotice.requested("clear"));
        assertEquals(KnkWeather.THUNDER, WeatherCommandNotice.requested("THUNDER"));
        assertNull(WeatherCommandNotice.requested("query"));
        assertNull(WeatherCommandNotice.requested(null));
    }

    @Test
    void noNoticeWithoutARule() {
        assertTrue(WeatherCommandNotice.notice("world", null, KnkWeather.CLEAR, 30).isEmpty());
        assertTrue(WeatherCommandNotice.notice("world", KnkWeatherSettings.normal(), KnkWeather.CLEAR, 30).isEmpty());
        assertTrue(WeatherCommandNotice.notice("world", CONSTANT_RAIN, null, 30).isEmpty());
    }

    @Test
    void constant_WarnsThatADifferentWeatherIsSwitchedBack() {
        assertEquals("&6Game Settings: &eweather in &fworld &eis &fConstant (rain)&e.\n"
                + "&cclear will be switched back to rain within 30 s.",
            WeatherCommandNotice.notice("world", CONSTANT_RAIN, KnkWeather.CLEAR, 30).orElseThrow());
        assertTrue(WeatherCommandNotice.notice("world", CONSTANT_RAIN, KnkWeather.RAIN, 30).orElseThrow()
            .endsWith("&7rain is allowed by the rule."));
    }

    @Test
    void blocked_OnlyTheBlockedKindIsSwitchedBack() {
        assertTrue(WeatherCommandNotice.notice("world", BLOCKED_THUNDER, KnkWeather.THUNDER, 30).orElseThrow()
            .endsWith("&cthunder will be switched back to clear within 30 s."));
        assertTrue(WeatherCommandNotice.notice("world", BLOCKED_THUNDER, KnkWeather.RAIN, 30).orElseThrow()
            .contains("Blocked (thunder)"));
    }

    @Test
    void weighted_ExplainsTheNextNaturalChange_OrAZeroWeight() {
        assertTrue(WeatherCommandNotice.notice("world", WEIGHTED_THUNDER, KnkWeather.THUNDER, 30).orElseThrow()
            .endsWith("&7thunder lasts until the next natural change; then the weights pick again."));
        // A weight of 0 is switched away from at refresh.
        assertTrue(WeatherCommandNotice.notice("world", WEIGHTED_THUNDER, KnkWeather.CLEAR, 30).orElseThrow()
            .contains("Weighted (clear 0 / rain 0 / thunder 100)"));
    }
}

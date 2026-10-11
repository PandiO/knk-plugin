package net.knightsandkings.knk.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.settings.KnkWeather;
import net.knightsandkings.knk.core.domain.settings.KnkWeatherSettings;
import net.knightsandkings.knk.core.domain.settings.KnkWeatherSettings.Mode;
import net.knightsandkings.knk.core.settings.WeatherRules.Cause;
import net.knightsandkings.knk.core.settings.WeatherRules.Verdict;

/** docs/specs/game-settings/DESIGN.md §3.5 - the per-world weather rule (KNG-52). */
class WeatherRulesTest {

    private static KnkWeatherSettings constant(KnkWeather weather) {
        return new KnkWeatherSettings(Mode.CONSTANT, weather, Set.of(), 0, 0, 0);
    }

    private static KnkWeatherSettings blocked(KnkWeather... weather) {
        return new KnkWeatherSettings(Mode.BLOCKED, null, Set.of(weather), 0, 0, 0);
    }

    private static KnkWeatherSettings weighted(int clear, int rain, int thunder) {
        return new KnkWeatherSettings(Mode.WEIGHTED, null, Set.of(), clear, rain, thunder);
    }

    @Test
    void normalAndMissingRulesAllowEverything() {
        assertEquals(Verdict.ALLOW, WeatherRules.onRainChange(null, true, false, Cause.NATURAL));
        assertEquals(Verdict.ALLOW, WeatherRules.onRainChange(KnkWeatherSettings.normal(), true, true, Cause.NATURAL));
        assertEquals(Verdict.ALLOW, WeatherRules.onThunderChange(KnkWeatherSettings.normal(), true, true, Cause.NATURAL));
        assertEquals(Optional.empty(), WeatherRules.enforce(KnkWeatherSettings.normal(), KnkWeather.THUNDER));
    }

    @Test
    void commandsAndPluginsAlwaysPass() {
        assertEquals(Verdict.ALLOW, WeatherRules.onRainChange(constant(KnkWeather.CLEAR), true, false, Cause.OTHER));
        assertEquals(Verdict.ALLOW, WeatherRules.onThunderChange(blocked(KnkWeather.THUNDER), true, true, Cause.OTHER));
        assertEquals(Verdict.ALLOW, WeatherRules.onRainChange(weighted(1, 1, 1), true, false, Cause.OTHER));
    }

    @Test
    void constantKeepsTheForcedWeather() {
        KnkWeatherSettings clear = constant(KnkWeather.CLEAR);
        assertEquals(Verdict.CANCEL, WeatherRules.onRainChange(clear, true, false, Cause.NATURAL));
        assertEquals(Verdict.ALLOW, WeatherRules.onRainChange(clear, false, false, Cause.NATURAL));
        // A thunder flag without rain shows nothing: still clear.
        assertEquals(Verdict.ALLOW, WeatherRules.onThunderChange(clear, false, true, Cause.NATURAL));

        KnkWeatherSettings rain = constant(KnkWeather.RAIN);
        assertEquals(Verdict.CANCEL, WeatherRules.onRainChange(rain, false, false, Cause.SLEEP));
        assertEquals(Verdict.CANCEL, WeatherRules.onThunderChange(rain, true, true, Cause.NATURAL));

        assertEquals(Optional.of(KnkWeather.THUNDER), WeatherRules.enforce(constant(KnkWeather.THUNDER), KnkWeather.RAIN));
        assertEquals(Optional.empty(), WeatherRules.enforce(rain, KnkWeather.RAIN));
    }

    @Test
    void constantWithoutAForcedWeatherMeansClear() {
        assertEquals(Optional.of(KnkWeather.CLEAR), WeatherRules.enforce(constant(null), KnkWeather.RAIN));
    }

    @Test
    void blockedWeatherNeverStarts() {
        KnkWeatherSettings noThunder = blocked(KnkWeather.THUNDER);
        assertEquals(Verdict.CANCEL, WeatherRules.onThunderChange(noThunder, true, true, Cause.NATURAL));
        assertEquals(Verdict.ALLOW, WeatherRules.onRainChange(noThunder, true, false, Cause.NATURAL));
        assertEquals(Verdict.ALLOW, WeatherRules.onThunderChange(noThunder, false, true, Cause.NATURAL));

        KnkWeatherSettings noClear = blocked(KnkWeather.CLEAR);
        assertEquals(Verdict.CANCEL, WeatherRules.onRainChange(noClear, false, false, Cause.SLEEP));

        assertEquals(Optional.of(KnkWeather.CLEAR), WeatherRules.enforce(noThunder, KnkWeather.THUNDER));
        assertEquals(Optional.of(KnkWeather.RAIN), WeatherRules.enforce(noClear, KnkWeather.CLEAR));
        assertEquals(Optional.empty(), WeatherRules.enforce(noThunder, KnkWeather.RAIN));
    }

    @Test
    void blockingEverythingIsIgnored() {
        KnkWeatherSettings all = blocked(KnkWeather.CLEAR, KnkWeather.RAIN, KnkWeather.THUNDER);
        assertEquals(Verdict.ALLOW, WeatherRules.onRainChange(all, true, false, Cause.NATURAL));
        assertEquals(Optional.empty(), WeatherRules.enforce(all, KnkWeather.CLEAR));
    }

    @Test
    void weightedRerollsTheNaturalCycleButLetsSleepClearIt() {
        KnkWeatherSettings settings = weighted(50, 30, 20);
        assertEquals(Verdict.CANCEL_AND_PICK, WeatherRules.onRainChange(settings, true, false, Cause.NATURAL));
        assertEquals(Verdict.CANCEL_AND_PICK, WeatherRules.onRainChange(settings, false, false, Cause.NATURAL));
        assertEquals(Verdict.CANCEL, WeatherRules.onThunderChange(settings, true, true, Cause.NATURAL));
        assertEquals(Verdict.ALLOW, WeatherRules.onRainChange(settings, false, false, Cause.SLEEP));
    }

    @Test
    void weightedWithoutWeightsIsVanilla() {
        KnkWeatherSettings none = weighted(0, 0, 0);
        assertEquals(Verdict.ALLOW, WeatherRules.onRainChange(none, true, false, Cause.NATURAL));
        assertEquals(KnkWeather.CLEAR, WeatherRules.pickWeighted(none, bound -> 0));
        assertEquals(Optional.empty(), WeatherRules.enforce(none, KnkWeather.THUNDER));
    }

    @Test
    void weightedPicksFollowTheWeights() {
        KnkWeatherSettings settings = weighted(50, 30, 20);
        assertEquals(KnkWeather.CLEAR, WeatherRules.pickWeighted(settings, bound -> 0));
        assertEquals(KnkWeather.CLEAR, WeatherRules.pickWeighted(settings, bound -> 49));
        assertEquals(KnkWeather.RAIN, WeatherRules.pickWeighted(settings, bound -> 50));
        assertEquals(KnkWeather.RAIN, WeatherRules.pickWeighted(settings, bound -> 79));
        assertEquals(KnkWeather.THUNDER, WeatherRules.pickWeighted(settings, bound -> 80));
        assertEquals(KnkWeather.THUNDER, WeatherRules.pickWeighted(settings, bound -> 99));
        assertEquals(KnkWeather.THUNDER, WeatherRules.pickWeighted(weighted(0, 0, 5), bound -> 0));
    }

    @Test
    void weightedLeavesAWeatherWithNoChanceForTheHeaviest() {
        assertEquals(Optional.of(KnkWeather.RAIN), WeatherRules.enforce(weighted(10, 80, 0), KnkWeather.THUNDER));
        assertTrue(WeatherRules.enforce(weighted(10, 80, 0), KnkWeather.CLEAR).isEmpty());
    }

    @Test
    void negativeWeightsCountAsZero() {
        KnkWeatherSettings settings = new KnkWeatherSettings(Mode.WEIGHTED, null, null, -5, 10, -1);
        assertEquals(10, settings.totalWeight());
        assertEquals(KnkWeather.RAIN, WeatherRules.pickWeighted(settings, bound -> 0));
    }
}

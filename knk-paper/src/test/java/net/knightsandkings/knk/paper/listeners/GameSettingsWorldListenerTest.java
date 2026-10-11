package net.knightsandkings.knk.paper.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.settings.WeatherRules;

/** Weather-change causes the Game Settings rule steers (docs/specs/game-settings/DESIGN.md §3.5). */
class GameSettingsWorldListenerTest {

    @Test
    void onlyNaturalAndSleepChangesAreSteered() {
        for (WeatherChangeEvent.Cause cause : WeatherChangeEvent.Cause.values()) {
            WeatherRules.Cause expected = switch (cause.name()) {
                case "NATURAL" -> WeatherRules.Cause.NATURAL;
                case "SLEEP" -> WeatherRules.Cause.SLEEP;
                default -> WeatherRules.Cause.OTHER;
            };
            assertEquals(expected, GameSettingsWorldListener.cause(cause.name()), cause.name());
        }
        // Both events name their causes the same way; the listener maps them by name.
        assertEquals(WeatherRules.Cause.NATURAL, GameSettingsWorldListener.cause(ThunderChangeEvent.Cause.NATURAL.name()));
        assertEquals(WeatherRules.Cause.SLEEP, GameSettingsWorldListener.cause(ThunderChangeEvent.Cause.SLEEP.name()));
        assertEquals(WeatherRules.Cause.OTHER, GameSettingsWorldListener.cause(ThunderChangeEvent.Cause.PLUGIN.name()));
        assertEquals(WeatherRules.Cause.OTHER, GameSettingsWorldListener.cause(ThunderChangeEvent.Cause.COMMAND.name()));
    }
}

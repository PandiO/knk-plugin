package net.knightsandkings.knk.core.domain.settings;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * A world's weather rule from the Game Settings page (knk-web-api {@code WorldWeatherSettingsDto}),
 * applied by knk-plugin (docs/specs/game-settings/DESIGN.md §3.5).
 *
 * @param mode          how the weather is controlled; never null
 * @param forcedWeather {@link Mode#CONSTANT}: the weather to keep; null means clear
 * @param blocked       {@link Mode#BLOCKED}: weather that may not start; never null
 * @param clearWeight   {@link Mode#WEIGHTED}: relative chance of clear at each natural change
 * @param rainWeight    relative chance of rain
 * @param thunderWeight relative chance of a thunderstorm
 */
public record KnkWeatherSettings(Mode mode, KnkWeather forcedWeather, Set<KnkWeather> blocked,
                                 int clearWeight, int rainWeight, int thunderWeight) {

    public enum Mode {
        /** Vanilla weather. */
        NORMAL,
        /** One weather, always. */
        CONSTANT,
        /** Vanilla weather, except the blocked kinds never start. */
        BLOCKED,
        /** Each natural change picks clear/rain/thunder by weight. */
        WEIGHTED;

        /** The API's value ({@code "Normal"}, {@code "Constant"}...), any case; {@link #NORMAL} when blank or unknown. */
        public static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return NORMAL;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return NORMAL;
            }
        }
    }

    public KnkWeatherSettings {
        mode = mode != null ? mode : Mode.NORMAL;
        blocked = blocked == null || blocked.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(blocked));
        clearWeight = Math.max(0, clearWeight);
        rainWeight = Math.max(0, rainWeight);
        thunderWeight = Math.max(0, thunderWeight);
    }

    /** Vanilla weather (the API's default). */
    public static KnkWeatherSettings normal() {
        return new KnkWeatherSettings(Mode.NORMAL, null, Set.of(), 34, 33, 33);
    }

    public int weight(KnkWeather weather) {
        return switch (weather) {
            case CLEAR -> clearWeight;
            case RAIN -> rainWeight;
            case THUNDER -> thunderWeight;
        };
    }

    public int totalWeight() {
        return clearWeight + rainWeight + thunderWeight;
    }
}

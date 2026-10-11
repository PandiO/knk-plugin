package net.knightsandkings.knk.core.settings;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import net.knightsandkings.knk.core.domain.settings.KnkWeather;
import net.knightsandkings.knk.core.domain.settings.KnkWeatherSettings;

/**
 * What a staff member is told before a {@code /weather} goes through in a world with a weather
 * rule from the Game Settings page (KNG-52 round 3, developer request 2026-10-09). Bukkit-free:
 * the text ({@code &}-coded) and what the next settings refresh will do to the new weather.
 */
public final class WeatherCommandNotice {

    private WeatherCommandNotice() {
    }

    /**
     * The weather a {@code /weather} argument asks for: {@code clear}, {@code rain} or
     * {@code thunder} (any case); null for anything else (vanilla then prints its usage).
     */
    public static KnkWeather requested(String argument) {
        if (argument == null) {
            return null;
        }
        return switch (argument.trim().toLowerCase(Locale.ROOT)) {
            case "clear" -> KnkWeather.CLEAR;
            case "rain" -> KnkWeather.RAIN;
            case "thunder" -> KnkWeather.THUNDER;
            default -> null;
        };
    }

    /**
     * @param worldName      the world the command changes
     * @param rule           its weather rule; null or {@link KnkWeatherSettings.Mode#NORMAL} = no notice
     * @param requested      the weather asked for
     * @param refreshSeconds how often the settings are re-applied
     * @return the lines to show before asking for confirmation, or empty when no confirmation is needed
     */
    public static Optional<String> notice(String worldName, KnkWeatherSettings rule, KnkWeather requested, int refreshSeconds) {
        if (rule == null || rule.mode() == KnkWeatherSettings.Mode.NORMAL || requested == null) {
            return Optional.empty();
        }
        String effect = WeatherRules.enforce(rule, requested)
            .map(back -> "&c" + name(requested) + " will be switched back to " + name(back) + " within " + refreshSeconds + " s.")
            .orElseGet(() -> rule.mode() == KnkWeatherSettings.Mode.WEIGHTED
                ? "&7" + name(requested) + " lasts until the next natural change; then the weights pick again."
                : "&7" + name(requested) + " is allowed by the rule.");
        return Optional.of("&6Game Settings: &eweather in &f" + worldName + " &eis &f" + describe(rule) + "&e.\n" + effect);
    }

    /** The rule as the page shows it, e.g. "Constant (rain)" or "Weighted (clear 0 / rain 0 / thunder 100)". */
    static String describe(KnkWeatherSettings rule) {
        return switch (rule.mode()) {
            case NORMAL -> "Normal";
            case CONSTANT -> "Constant (" + name(rule.forcedWeather() != null ? rule.forcedWeather() : KnkWeather.CLEAR) + ")";
            case BLOCKED -> rule.blocked().isEmpty() ? "Blocked (nothing)"
                : "Blocked (" + rule.blocked().stream().sorted().map(WeatherCommandNotice::name).collect(Collectors.joining(", ")) + ")";
            case WEIGHTED -> "Weighted (clear " + rule.clearWeight() + " / rain " + rule.rainWeight()
                + " / thunder " + rule.thunderWeight() + ")";
        };
    }

    private static String name(KnkWeather weather) {
        return weather.name().toLowerCase(Locale.ROOT);
    }
}

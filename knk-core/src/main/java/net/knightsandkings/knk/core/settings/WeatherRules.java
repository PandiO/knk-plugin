package net.knightsandkings.knk.core.settings;

import java.util.Optional;
import java.util.function.IntUnaryOperator;

import net.knightsandkings.knk.core.domain.settings.KnkWeather;
import net.knightsandkings.knk.core.domain.settings.KnkWeatherSettings;

/**
 * The per-world weather rule (docs/specs/game-settings/DESIGN.md §3.5), Bukkit-free.
 * <p>
 * Only the server's own changes are steered: the natural weather cycle and sleeping through a storm.
 * A staff {@code /weather} or another plugin's change is let through; a {@code CONSTANT} or
 * {@code BLOCKED} rule puts the weather back at the next settings refresh ({@link #enforce}).
 */
public final class WeatherRules {

    /** Why the weather is about to change. */
    public enum Cause {
        /** The vanilla weather cycle. */
        NATURAL,
        /** Players slept through the night. */
        SLEEP,
        /** A command, a plugin (including this one) or unknown. */
        OTHER
    }

    public enum Verdict {
        ALLOW,
        CANCEL,
        /** Cancel the change and set a weighted pick instead ({@link #pickWeighted}). */
        CANCEL_AND_PICK
    }

    private WeatherRules() {
    }

    /**
     * Rain starting or stopping ({@code WeatherChangeEvent}).
     *
     * @param toStorm    the rain state the world is changing to
     * @param thundering the thunder flag now (it is not part of this change)
     */
    public static Verdict onRainChange(KnkWeatherSettings settings, boolean toStorm, boolean thundering, Cause cause) {
        if (settings == null || cause == Cause.OTHER) {
            return Verdict.ALLOW;
        }
        KnkWeather to = KnkWeather.of(toStorm, thundering);
        return switch (settings.mode()) {
            case NORMAL -> Verdict.ALLOW;
            case CONSTANT -> to == forced(settings) ? Verdict.ALLOW : Verdict.CANCEL;
            case BLOCKED -> allBlocked(settings) || !settings.blocked().contains(to) ? Verdict.ALLOW : Verdict.CANCEL;
            // Sleeping through a storm stays the players' choice; only the cycle itself is re-rolled.
            case WEIGHTED -> settings.totalWeight() <= 0 || cause == Cause.SLEEP ? Verdict.ALLOW : Verdict.CANCEL_AND_PICK;
        };
    }

    /**
     * The thunder flag flipping ({@code ThunderChangeEvent}).
     *
     * @param storm     whether it is raining now (not part of this change)
     * @param toThunder the thunder state the world is changing to
     */
    public static Verdict onThunderChange(KnkWeatherSettings settings, boolean storm, boolean toThunder, Cause cause) {
        if (settings == null || cause == Cause.OTHER) {
            return Verdict.ALLOW;
        }
        KnkWeather to = KnkWeather.of(storm, toThunder);
        return switch (settings.mode()) {
            case NORMAL -> Verdict.ALLOW;
            case CONSTANT -> to == forced(settings) ? Verdict.ALLOW : Verdict.CANCEL;
            case BLOCKED -> allBlocked(settings) || !settings.blocked().contains(to) ? Verdict.ALLOW : Verdict.CANCEL;
            // The thunder flag has its own vanilla timer; under WEIGHTED only the picks set it.
            case WEIGHTED -> settings.totalWeight() <= 0 || cause == Cause.SLEEP ? Verdict.ALLOW : Verdict.CANCEL;
        };
    }

    /**
     * The weather a world must be switched to now so it obeys the rule (applied at every settings
     * refresh), or empty when the current weather is fine.
     */
    public static Optional<KnkWeather> enforce(KnkWeatherSettings settings, KnkWeather current) {
        if (settings == null || current == null) {
            return Optional.empty();
        }
        return switch (settings.mode()) {
            case NORMAL -> Optional.empty();
            case CONSTANT -> current == forced(settings) ? Optional.empty() : Optional.of(forced(settings));
            case BLOCKED -> {
                if (allBlocked(settings) || !settings.blocked().contains(current)) {
                    yield Optional.empty();
                }
                for (KnkWeather candidate : KnkWeather.values()) {
                    if (!settings.blocked().contains(candidate)) {
                        yield Optional.of(candidate);
                    }
                }
                yield Optional.empty();
            }
            case WEIGHTED -> {
                // A weather with weight 0 never happens: leave it for the heaviest one.
                if (settings.totalWeight() <= 0 || settings.weight(current) > 0) {
                    yield Optional.empty();
                }
                KnkWeather heaviest = KnkWeather.CLEAR;
                for (KnkWeather candidate : KnkWeather.values()) {
                    if (settings.weight(candidate) > settings.weight(heaviest)) {
                        heaviest = candidate;
                    }
                }
                yield Optional.of(heaviest);
            }
        };
    }

    /**
     * A weighted pick.
     *
     * @param random given a bound n &gt; 0, a number in [0, n)
     */
    public static KnkWeather pickWeighted(KnkWeatherSettings settings, IntUnaryOperator random) {
        int total = settings.totalWeight();
        if (total <= 0) {
            return KnkWeather.CLEAR;
        }
        int roll = random.applyAsInt(total);
        for (KnkWeather candidate : KnkWeather.values()) {
            roll -= settings.weight(candidate);
            if (roll < 0) {
                return candidate;
            }
        }
        return KnkWeather.THUNDER;
    }

    private static KnkWeather forced(KnkWeatherSettings settings) {
        return settings.forcedWeather() != null ? settings.forcedWeather() : KnkWeather.CLEAR;
    }

    /** Every kind blocked can't be obeyed; such a rule is ignored. */
    private static boolean allBlocked(KnkWeatherSettings settings) {
        return settings.blocked().size() >= KnkWeather.values().length;
    }
}

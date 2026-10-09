package net.knightsandkings.knk.core.domain.settings;

import java.util.Locale;

/** A world's weather as the Game Settings page names it ({@code CLEAR}, {@code RAIN}, {@code THUNDER}). */
public enum KnkWeather {
    CLEAR, RAIN, THUNDER;

    /** The state a world is in: no storm is clear (a thunder flag without rain shows nothing). */
    public static KnkWeather of(boolean storm, boolean thundering) {
        return !storm ? CLEAR : thundering ? THUNDER : RAIN;
    }

    /** The API's value, any case; null when blank or unknown. */
    public static KnkWeather parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

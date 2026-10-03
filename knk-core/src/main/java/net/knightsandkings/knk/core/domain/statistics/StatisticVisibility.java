package net.knightsandkings.knk.core.domain.statistics;

import java.util.Locale;

/**
 * Who may see one of a player's statistics (DESIGN.md §F.4). The API names are knk-web-api's
 * {@code StatisticVisibility} values. {@link #FRIENDS} fails closed (shows nothing) until the
 * friends system (KNG-35) exists.
 */
public enum StatisticVisibility {
    NOBODY("Nobody"),
    FRIENDS("Friends"),
    EVERYONE("Everyone");

    private final String apiName;

    StatisticVisibility(String apiName) {
        this.apiName = apiName;
    }

    public String apiName() {
        return apiName;
    }

    /** Nobody → Friends → Everyone → Nobody (the menu's click cycle). */
    public StatisticVisibility next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** An API or menu value, any case; unknown or missing → {@link #NOBODY} (the fail-closed default). */
    public static StatisticVisibility fromApiName(String name) {
        if (name == null) {
            return NOBODY;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (StatisticVisibility value : values()) {
            if (value.apiName.toLowerCase(Locale.ROOT).equals(wanted)) {
                return value;
            }
        }
        return NOBODY;
    }
}

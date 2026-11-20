package net.knightsandkings.knk.core.siege.economy;

import java.util.Locale;

/** Where siege points came from (DESIGN § 5.2, P15); the code goes to telemetry (P27), the label to the action bar. */
public enum SiegePointsSource {
    KILL("kill"),
    ASSIST("assist"),
    DEATH("death"),
    GATE_DAMAGE("gate"),
    CAPTURE_PROGRESS("objective"),
    CAPTURE("capture"),
    TRICKLE("activity"),
    /** A notable event (#612), credited through {@link SiegePointsEconomy#credit}. */
    NOTABLE_EVENT("event");

    private final String label;

    SiegePointsSource(String label) {
        this.label = label;
    }

    /** The short word shown after the amount ("+100 kill"). */
    public String label() {
        return label;
    }

    /** Stable lower-case code for telemetry ({@code capture_progress}). */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}

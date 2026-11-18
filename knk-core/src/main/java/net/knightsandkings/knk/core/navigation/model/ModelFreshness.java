package net.knightsandkings.knk.core.navigation.model;

import java.time.Duration;
import java.time.Instant;

import net.knightsandkings.knk.core.navigation.destinations.DestinationList;

/**
 * How current the navigation model is (destination engine DESIGN § 7.3, § 7.4): the {@code degraded} flag and text
 * the engine's lists carry.
 *
 * <ol>
 *   <li><b>Limited</b>: no model at all (no snapshot was ever written and the API has not answered): degraded, with
 *       "Some places aren't available while the web API is down." (§ 7.4).</li>
 *   <li><b>Stale</b>: the model is older than {@code navigation.destinations.stale-after-days} (default 14, E12):
 *       degraded, with a "may be out of date" note (§ 7.3). Nothing is hidden; destinations do not expire.</li>
 *   <li><b>Fresh</b>: not degraded, no text.</li>
 * </ol>
 *
 * @param state the case above
 * @param age   how old the model is; null in the limited case
 * @param text  the note for the list, null when fresh
 */
public record ModelFreshness(State state, Duration age, String text) {

    public enum State {
        FRESH,
        STALE,
        LIMITED
    }

    /** § 7.4, the text players see in limited mode. */
    public static final String LIMITED_TEXT = "Some places aren't available while the web API is down.";

    /** The default staleness limit (E12). */
    public static final int DEFAULT_STALE_AFTER_DAYS = 14;

    /**
     * @param model         the model now; null when none was ever loaded
     * @param now           the time now
     * @param staleAfter    the staleness limit (at least one day is used)
     */
    public static ModelFreshness of(NavigationModel model, Instant now, Duration staleAfter) {
        if (model == null) {
            return new ModelFreshness(State.LIMITED, null, LIMITED_TEXT);
        }
        Duration age = Duration.between(model.asOf(), now);
        if (age.isNegative()) {
            age = Duration.ZERO;
        }
        Duration limit = staleAfter == null || staleAfter.compareTo(Duration.ofDays(1)) < 0 ? Duration.ofDays(1) : staleAfter;
        if (age.compareTo(limit) > 0) {
            return new ModelFreshness(State.STALE, age, staleText(age));
        }
        return new ModelFreshness(State.FRESH, age, null);
    }

    /** "The list of places may be out of date (last updated 15 days ago)." */
    public static String staleText(Duration age) {
        long days = Math.max(1, age.toDays());
        return "The list of places may be out of date (last updated " + days + (days == 1 ? " day" : " days") + " ago).";
    }

    public boolean degraded() {
        return state != State.FRESH;
    }

    /** {@code list} as is when fresh, else marked degraded with this note (for the engine's adapters, phase 3). */
    public DestinationList applyTo(DestinationList list) {
        return list == null || !degraded() ? list : list.degraded(text);
    }

    /** A short form for logs and status lines: "fresh (2 h old)", "stale (15 days old)", "limited (no snapshot)". */
    public String describe() {
        return switch (state) {
            case LIMITED -> "limited (no snapshot)";
            case STALE -> "stale (" + ageText(age) + " old)";
            case FRESH -> "fresh (" + ageText(age) + " old)";
        };
    }

    static String ageText(Duration age) {
        if (age.toDays() >= 1) {
            return age.toDays() + (age.toDays() == 1 ? " day" : " days");
        }
        if (age.toHours() >= 1) {
            return age.toHours() + " h";
        }
        if (age.toMinutes() >= 1) {
            return age.toMinutes() + " min";
        }
        return age.toSeconds() + " s";
    }
}

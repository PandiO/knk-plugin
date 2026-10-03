package net.knightsandkings.knk.core.domain.statistics;

/**
 * A visibility update the API rejected with 409 {@code VisibilityConflict}: a setting no longer had
 * the expected value (changed on the web or in another menu). Nothing was written; {@link #current()}
 * is the player's settings as they are now, so the menu refreshes from it.
 */
public class StatisticsVisibilityConflictException extends RuntimeException {

    private final transient StatisticsVisibilitySettings current;

    public StatisticsVisibilityConflictException(String message, StatisticsVisibilitySettings current) {
        super(message);
        this.current = current;
    }

    /** The settings as the API holds them now; null when the 409 body couldn't be read. */
    public StatisticsVisibilitySettings current() {
        return current;
    }
}

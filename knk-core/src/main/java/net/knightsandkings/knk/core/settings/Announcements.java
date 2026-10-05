package net.knightsandkings.knk.core.settings;

import java.util.Optional;

/**
 * Join/leave broadcast text from the Game Settings page (docs/specs/game-settings/DESIGN.md §3.1):
 * {@code &}-coded legacy text with {@code {player}} filled in.
 */
public final class Announcements {

    /** The API's own defaults, used until the settings have been read once. */
    public static final String DEFAULT_JOIN = "&a{player} joined the server.";
    public static final String DEFAULT_LEAVE = "&e{player} left the server.";

    private Announcements() {
    }

    /**
     * @param template   the configured text; null means "not configured" (use {@code fallback}), blank means
     *                   "no broadcast"
     * @param fallback   used when {@code template} is null
     * @param playerName fills every {@code {player}}
     * @return the text to broadcast, or empty for none
     */
    public static Optional<String> render(String template, String fallback, String playerName) {
        String text = template != null ? template : fallback;
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(text.replace("{player}", playerName != null ? playerName : "Player"));
    }
}

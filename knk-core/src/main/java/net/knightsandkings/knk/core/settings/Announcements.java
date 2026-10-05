package net.knightsandkings.knk.core.settings;

import java.util.Optional;

/**
 * Text from the Game Settings page (docs/specs/game-settings/DESIGN.md §3.1, §3.9): {@code &}-coded
 * legacy text (several colours, hex as {@code &x&r&r&g&g&b&b}) with placeholders filled in.
 */
public final class Announcements {

    /** The API's own defaults, used until the settings have been read once. */
    public static final String DEFAULT_JOIN = "&a{player} joined the server.";
    public static final String DEFAULT_LEAVE = "&e{player} left the server.";

    private Announcements() {
    }

    /** {@link #render(String, String, String, String)} without a group name. */
    public static Optional<String> render(String template, String fallback, String playerName) {
        return render(template, fallback, playerName, "");
    }

    /**
     * A join or leave broadcast.
     *
     * @param template   the configured text; null means "not configured" (use {@code fallback}), blank means
     *                   "no broadcast"
     * @param fallback   used when {@code template} is null
     * @param playerName fills every {@code {player}}
     * @param groupName  fills every {@code {group}} (KNG-52); null = blank
     * @return the text to broadcast, or empty for none
     */
    public static Optional<String> render(String template, String fallback, String playerName, String groupName) {
        String text = template != null ? template : fallback;
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(text
            .replace("{player}", playerName != null ? playerName : "Player")
            .replace("{group}", groupName != null ? groupName : ""));
    }

    /**
     * The server-list MOTD (KNG-52): {@code {online}} and {@code {max}} filled in, at most two lines.
     *
     * @return empty when no MOTD is configured (the server's own is shown)
     */
    public static Optional<String> renderMotd(String motd, int online, int max) {
        if (motd == null || motd.isBlank()) {
            return Optional.empty();
        }
        String[] lines = motd.replace("\r\n", "\n").split("\n", -1);
        String text = lines.length > 2 ? lines[0] + "\n" + lines[1] : String.join("\n", lines);
        return Optional.of(text.replace("{online}", Integer.toString(online)).replace("{max}", Integer.toString(max)));
    }
}

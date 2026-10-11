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
        return render(template, fallback, playerName, groupName, null);
    }

    /**
     * {@link #render(String, String, String, String)} plus the player's title (KNG-52 round 3):
     * {@code {title}} (alias {@code {titlename}}), e.g. "Knight". An empty {@code {group}} or
     * {@code {title}} also takes one neighbouring space, so "{group} {title} {player}" never shows
     * a double space.
     *
     * @param title fills every {@code {title}}/{@code {titlename}}; null = blank
     */
    public static Optional<String> render(String template, String fallback, String playerName, String groupName,
                                          String title) {
        String text = template != null ? template : fallback;
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        text = text.replace("{titlename}", "{title}");
        text = fill(text, "{group}", groupName);
        text = fill(text, "{title}", title);
        return Optional.of(text.replace("{player}", playerName != null ? playerName : "Player"));
    }

    private static String fill(String text, String placeholder, String value) {
        if (value != null && !value.isBlank()) {
            return text.replace(placeholder, value.trim());
        }
        return text.replace(placeholder + " ", "").replace(" " + placeholder, "").replace(placeholder, "");
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

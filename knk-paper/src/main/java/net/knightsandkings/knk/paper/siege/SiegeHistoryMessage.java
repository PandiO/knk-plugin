package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.ports.api.SiegeMatchHistoryApi.PlayerMatch;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * {@code /siege history} (alpha run 1, PandiO/knk-workspace#174): the player's last matches, one line each, with a
 * clickable link to the match page in the web app (when {@code web.public-url} is set) and to their whole list on
 * their public profile. Pure formatting, unit-tested.
 */
public final class SiegeHistoryMessage {

    /** How many matches the command lists. */
    public static final int LIMIT = 5;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);

    private SiegeHistoryMessage() {
    }

    /**
     * @param publicWebUrl {@code web.public-url} (blank: no links)
     * @param zone         the server's time zone, for the dates
     */
    public static Component render(String playerName, List<PlayerMatch> matches, String publicWebUrl, ZoneId zone) {
        Component msg = SiegeMessages.prefixed(Component.text("Your last siege matches:", SiegeMessages.INFO));
        if (matches.isEmpty()) {
            return msg.append(Component.newline())
                    .append(Component.text("  You haven't finished a siege match yet. Join one with ", SiegeMessages.INFO))
                    .append(SiegeMessages.command("/siege join"));
        }
        for (PlayerMatch m : matches) {
            msg = msg.append(Component.newline()).append(line(m, publicWebUrl, zone));
        }
        String profile = profileUrl(publicWebUrl, playerName);
        if (profile != null) {
            msg = msg.append(Component.newline())
                    .append(Component.text("  All your matches: ", SiegeMessages.INFO))
                    .append(Component.text("[your profile]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(profile))
                            .hoverEvent(HoverEvent.showText(Component.text("Open " + profile, SiegeMessages.INFO))));
        }
        return msg;
    }

    static Component line(PlayerMatch m, String publicWebUrl, ZoneId zone) {
        String outcome = m.outcome() == null ? "?" : m.outcome();
        Component line = Component.text("  " + date(m.endedAt(), zone) + " ", NamedTextColor.DARK_GRAY)
                .append(Component.text(outcomeLabel(outcome), outcomeColor(outcome), TextDecoration.BOLD))
                .append(Component.text(" " + (m.scenarioName() == null ? "Siege" : m.scenarioName()), NamedTextColor.WHITE)
                        .decoration(TextDecoration.BOLD, false));
        if (m.teamName() != null) {
            line = line.append(Component.text(" (", SiegeMessages.INFO))
                    .append(Component.text(m.teamName(), SiegeBukkit.color(m.teamChatColor())))
                    .append(Component.text(")", SiegeMessages.INFO));
        }
        line = line.append(Component.text(" " + number(m.kills()) + " K / " + number(m.deaths()) + " D", SiegeMessages.INFO));
        String url = SiegeResultMessage.matchPageUrl(publicWebUrl, m.matchId());
        if (url != null) {
            line = line.append(Component.space())
                    .append(Component.text("[details]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(Component.text("Open match #" + m.matchId() + " in the web app", SiegeMessages.INFO))));
        }
        return line;
    }

    static String profileUrl(String publicWebUrl, String playerName) {
        if (publicWebUrl == null || publicWebUrl.isBlank() || playerName == null) return null;
        String base = publicWebUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/players/" + java.net.URLEncoder.encode(playerName, java.nio.charset.StandardCharsets.UTF_8);
    }

    static String outcomeLabel(String outcome) {
        return switch (outcome) {
            case "Win" -> "Win";
            case "Loss" -> "Loss";
            case "Draw" -> "Draw";
            case "Left" -> "Left";
            case "Aborted" -> "Aborted";
            default -> outcome;
        };
    }

    private static NamedTextColor outcomeColor(String outcome) {
        return switch (outcome) {
            case "Win" -> NamedTextColor.GREEN;
            case "Loss" -> NamedTextColor.RED;
            case "Draw" -> NamedTextColor.YELLOW;
            default -> NamedTextColor.GRAY;
        };
    }

    private static String number(Integer value) {
        return value == null ? "?" : String.valueOf(value);
    }

    private static String date(Instant at, ZoneId zone) {
        return at == null ? "-" : DATE.format(at.atZone(zone == null ? ZoneId.systemDefault() : zone));
    }
}

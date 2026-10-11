package net.knightsandkings.knk.paper.menu.content;

import java.util.ArrayList;
import java.util.List;

import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardView;
import net.knightsandkings.knk.core.statistics.StatisticsLines;

/**
 * One ranked player of {@code statistics.leaderboard} (row source {@code statistics.leaderboard.entries}):
 * their head, rank and value; the viewer's own row is HIGHLIGHTed. A click opens their
 * {@code statistics.main} ({@code ctx.target} = {@link #getUserId()}, {@code ctx.name} = {@link #getUsername()}).
 */
public final class LeaderboardEntryRow {

    private final LeaderboardView.Entry entry;
    private final String unit;
    private final String metric;
    private final boolean viewer;

    LeaderboardEntryRow(LeaderboardView.Entry entry, String unit, String metric, boolean viewer) {
        this.entry = entry;
        this.unit = unit;
        this.metric = metric;
        this.viewer = viewer;
    }

    public int getUserId() {
        return entry.userId();
    }

    public String getUsername() {
        return entry.username();
    }

    public int getRank() {
        return entry.rank();
    }

    public String getDisplayMode() {
        return viewer ? "HIGHLIGHT" : "NORMAL";
    }

    public String getName() {
        String color = switch (entry.rank()) {
            case 1 -> "&6";
            case 2 -> "&f";
            case 3 -> "&c";
            default -> "&7";
        };
        return color + "#" + entry.rank() + " &f" + entry.username() + (viewer ? " &a(you)" : "");
    }

    public String getValue() {
        return StatisticsLines.format(metric, unit, entry.value());
    }

    public List<String> getLoreLines() {
        List<String> lore = new ArrayList<>();
        lore.add("&7" + getValue());
        lore.add("");
        lore.add("&eClick: &7view statistics");
        return lore;
    }
}

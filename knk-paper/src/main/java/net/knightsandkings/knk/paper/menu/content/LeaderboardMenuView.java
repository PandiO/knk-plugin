package net.knightsandkings.knk.paper.menu.content;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardView;
import net.knightsandkings.knk.core.statistics.StatisticsLines;

/**
 * The {@code lb} root of {@code statistics.leaderboard}: the board's name, the shown period (and the
 * next one of the cycle) and the viewer's own position. Built from the last read; "Loading" until then.
 */
public final class LeaderboardMenuView {

    private final String label;
    private final String period;
    private final LeaderboardView view;
    private final boolean loaded;
    private final Instant now;

    LeaderboardMenuView(String label, String period, LeaderboardView view, boolean loaded, Instant now) {
        this.label = label;
        this.period = period;
        this.view = view;
        this.loaded = loaded;
        this.now = now;
    }

    public String getTitle() {
        return "&f" + label;
    }

    public String getPeriodName() {
        return switch (period) {
            case "weekly" -> "This week";
            case "monthly" -> "This month";
            default -> "All time";
        };
    }

    public String getNextPeriodLine() {
        return "&eClick: &7show " + switch (LeaderboardsMenuFeature.nextPeriod(period)) {
            case "weekly" -> "this week";
            case "monthly" -> "this month";
            default -> "all time";
        };
    }

    /** Your rank and value, why you're not ranked, or the load state. */
    public List<String> getViewerLines() {
        List<String> lines = new ArrayList<>();
        if (!loaded) {
            lines.add("&7Loading...");
            return lines;
        }
        if (view == null) {
            lines.add("&cCouldn't load this leaderboard - try again later");
            return lines;
        }
        if (view.viewer() != null) {
            lines.add("&7Your rank: &f#" + view.viewer().rank() + " &7of &f" + view.totalRanked());
            lines.add("&7Your value: &f" + StatisticsLines.format(metric(), view.unit(), view.viewer().value()));
        } else {
            lines.add("&7You're not ranked here (" + view.totalRanked() + " ranked)");
            lines.add("&8Only players who show this statistic");
            lines.add("&8to everyone are ranked (/stats settings)");
        }
        if (view.generatedAt() != null) {
            lines.add("&8Updated " + ago(view.generatedAt(), now));
        } else {
            lines.add("&8Not computed yet - check back in a few minutes");
        }
        return lines;
    }

    private String metric() {
        String key = view == null || view.boardKey() == null ? "" : view.boardKey();
        int at = key.indexOf('@');
        return at < 0 ? key : key.substring(0, at);
    }

    private static String ago(Instant at, Instant now) {
        long minutes = Math.max(0, Duration.between(at, now).toMinutes());
        return minutes < 1 ? "just now" : minutes + " min ago";
    }
}

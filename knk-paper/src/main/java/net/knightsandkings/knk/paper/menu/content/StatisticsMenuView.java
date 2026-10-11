package net.knightsandkings.knk.paper.menu.content;

import java.util.ArrayList;
import java.util.List;

import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.statistics.StatisticsLines;

/**
 * The {@code stats} root of {@code statistics.main}: the last read {@link StatisticsMenuFeature} made
 * for the viewer, as lore lines per catalogue group ({@link StatisticsLines}). Only what the API
 * returned for this viewer appears; "Loading" until the read arrives.
 */
public final class StatisticsMenuView {

    static final String NOTHING_VISIBLE = "&8Nothing visible";

    private final String targetName;
    private final boolean own;
    private final String period;
    private final PlayerStatistics statistics;
    private final boolean historyHidden;
    private final boolean loaded;

    StatisticsMenuView(String targetName, boolean own, String period, PlayerStatistics statistics, boolean historyHidden,
                       boolean loaded) {
        this.targetName = statistics != null && statistics.username() != null ? statistics.username() : targetName;
        this.own = own;
        this.period = period == null ? "lifetime" : period;
        this.statistics = statistics;
        this.historyHidden = historyHidden;
        this.loaded = loaded;
    }

    static StatisticsMenuView loading(String targetName, boolean own, String period) {
        return new StatisticsMenuView(targetName, own, period, null, false, false);
    }

    /** For the player head's skull owner; empty while unknown. */
    public String getTargetName() {
        return targetName == null ? "" : targetName;
    }

    public String getTitle() {
        if (own) {
            return "&fYour statistics";
        }
        return targetName == null ? "&fStatistics" : "&f" + targetName + "&7's statistics";
    }

    public List<String> getProfileLines() {
        if (statistics == null) {
            return List.of(status());
        }
        return StatisticsLines.profileLines(statistics.profile());
    }

    public String getPeriodLine() {
        return "&7Showing: &f" + StatisticsLines.periodLabel(period, statistics == null ? null : statistics.periodStart());
    }

    public String getNextPeriodLine() {
        return "&eClick: &7show " + switch (StatisticsLines.nextPeriod(period)) {
            case "day" -> "today";
            case "week" -> "this week";
            case "month" -> "this month";
            default -> "all time";
        };
    }

    public List<String> getActivityLines() {
        return group(StatisticsLines.ACTIVITY);
    }

    public List<String> getCombatLines() {
        return group(StatisticsLines.COMBAT);
    }

    public List<String> getMinigamesLines() {
        return group(StatisticsLines.MINIGAMES);
    }

    public List<String> getExplorationLines() {
        return group(StatisticsLines.EXPLORATION);
    }

    public List<String> getProgressionLines() {
        List<String> lines = new ArrayList<>(group(StatisticsLines.PROGRESSION));
        List<String> other = statistics == null ? List.of() : StatisticsLines.groupLines(statistics, StatisticsLines.OTHER);
        if (!other.isEmpty()) {
            lines.remove(NOTHING_VISIBLE);
            lines.addAll(other);
        }
        return lines;
    }

    /** Above the title-history rows: why the list is empty when the player keeps it private. */
    public String getTitleHistoryLine() {
        if (!loaded) {
            return "&7Loading...";
        }
        if (historyHidden) {
            return "&8" + (own ? "Your" : getTargetName() + "'s") + " title history isn't visible to you";
        }
        return "&7Title changes, newest first (below)";
    }

    private List<String> group(String group) {
        if (statistics == null) {
            return List.of(status());
        }
        List<String> lines = StatisticsLines.groupLines(statistics, group);
        return lines.isEmpty() ? List.of(NOTHING_VISIBLE) : lines;
    }

    private String status() {
        return loaded ? "&cCouldn't load statistics - try again later" : "&7Loading...";
    }
}

package net.knightsandkings.knk.paper.menu.content;

import java.util.ArrayList;
import java.util.List;

import net.knightsandkings.knk.core.domain.statistics.StatisticVisibility;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilitySettings;

/**
 * The {@code statsvis} root of {@code statistics.visibility}: which group is selected (its selector
 * item is HIGHLIGHTed), a count line for it, and the preview of a pending group action
 * ({@code setting: current → proposed}, shown on the Confirm button). Built from the last settings
 * {@link StatisticsVisibilityMenuFeature} read; "loading" until they arrive.
 */
public final class StatisticsVisibilityView {

    private final String selectedGroup;
    private final String selectedLabel;
    private final StatisticsVisibilitySettings settings;
    private final List<String> previewLines;

    StatisticsVisibilityView(String selectedGroup, String selectedLabel, StatisticsVisibilitySettings settings,
                             List<String> previewLines) {
        this.selectedGroup = selectedGroup;
        this.selectedLabel = selectedLabel;
        this.settings = settings;
        this.previewLines = previewLines == null ? List.of() : List.copyOf(previewLines);
    }

    public String getSelectedGroupLabel() {
        return selectedLabel;
    }

    public String getActivityMode() {
        return mode("activity");
    }

    public String getCombatMode() {
        return mode("combat");
    }

    public String getMinigamesMode() {
        return mode("minigames");
    }

    public String getExplorationMode() {
        return mode("exploration");
    }

    public String getProgressionMode() {
        return mode("progression");
    }

    private String mode(String group) {
        return group.equalsIgnoreCase(selectedGroup) ? "HIGHLIGHT" : "NORMAL";
    }

    /** "&7Nobody &f3 &8| &7Friends &f0 &8| &7Everyone &f1" over the selected group's settings. */
    public List<String> getGroupSummaryLines() {
        List<String> lines = new ArrayList<>();
        if (settings == null) {
            lines.add("&7Loading your settings...");
            return lines;
        }
        int[] counts = new int[StatisticVisibility.values().length];
        for (StatisticsVisibilitySettings.Setting setting : settings.settings()) {
            if (setting.group() != null && setting.group().equalsIgnoreCase(selectedGroup)) {
                counts[setting.visibility().ordinal()]++;
            }
        }
        lines.add("&7Showing: &f" + selectedLabel);
        lines.add("&cNobody &f" + counts[0] + " &8| &bFriends &f" + counts[1] + " &8| &aEveryone &f" + counts[2]);
        return lines;
    }

    /** The pending group action, one line per affected setting; a hint when nothing is pending. */
    public List<String> getPreviewLines() {
        return previewLines.isEmpty() ? List.of("&7Nothing to confirm") : previewLines;
    }
}

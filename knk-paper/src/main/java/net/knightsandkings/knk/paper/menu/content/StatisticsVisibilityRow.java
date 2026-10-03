package net.knightsandkings.knk.paper.menu.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.knightsandkings.knk.core.domain.statistics.StatisticVisibility;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilitySettings;

/**
 * One row of {@code statistics.visibility} (row source {@code statistics.visibility.rows}): a
 * setting's metric-level value, or one context of a contextual setting ({@code Player kills — Siege})
 * with its override or the inherited value. Clicking cycles Nobody → Friends → Everyone
 * ({@code statistics.visibility.cycle} with {@link #getSettingKey()}, {@link #getContext()} and the
 * shown value {@link #getVisibility()} as the expected one).
 */
public final class StatisticsVisibilityRow {

    static final String FRIENDS_NOTE = "&8Friends-only shows nothing until the friends system exists";

    private final String settingKey;
    private final String context;
    private final String label;
    private final StatisticVisibility visibility;
    private final boolean override;
    private final boolean friendsAvailable;

    StatisticsVisibilityRow(String settingKey, String context, String label, StatisticVisibility visibility,
                            boolean override, boolean friendsAvailable) {
        this.settingKey = settingKey;
        this.context = context == null ? "" : context;
        this.label = label;
        this.visibility = visibility == null ? StatisticVisibility.NOBODY : visibility;
        this.override = override;
        this.friendsAvailable = friendsAvailable;
    }

    /** The metric-level row of a setting. */
    static StatisticsVisibilityRow of(StatisticsVisibilitySettings.Setting setting, boolean friendsAvailable) {
        return new StatisticsVisibilityRow(setting.settingKey(), "", setting.label(), setting.visibility(), false, friendsAvailable);
    }

    /** One context row of a contextual setting. */
    static StatisticsVisibilityRow of(StatisticsVisibilitySettings.Setting setting, StatisticsVisibilitySettings.ContextValue value,
                                      boolean friendsAvailable) {
        return new StatisticsVisibilityRow(setting.settingKey(), value.context(), setting.label(), value.visibility(),
                value.isOverride(), friendsAvailable);
    }

    public String getSettingKey() {
        return settingKey;
    }

    /** {@code ""} for the metric-level row. */
    public String getContext() {
        return context;
    }

    /** The shown value as the API names it ({@code Nobody}/{@code Friends}/{@code Everyone}). */
    public String getVisibility() {
        return visibility.apiName();
    }

    StatisticVisibility visibility() {
        return visibility;
    }

    public boolean isContextRow() {
        return !context.isEmpty();
    }

    public String getMaterial() {
        return switch (visibility) {
            case NOBODY -> "GRAY_DYE";
            case FRIENDS -> "LIGHT_BLUE_DYE";
            case EVERYONE -> "LIME_DYE";
        };
    }

    public String getDisplayMode() {
        return "NORMAL";
    }

    public String getName() {
        return isContextRow() ? "&f" + label + " &7— &f" + contextLabel(context) : "&f" + label;
    }

    public List<String> getLoreLines() {
        List<String> lore = new ArrayList<>();
        lore.add("&7Visible to: " + colored(visibility));
        if (isContextRow()) {
            lore.add(override ? "&7Set for " + contextLabel(context) + " only" : "&7Same as " + label + " (inherited)");
        }
        if (visibility == StatisticVisibility.FRIENDS && !friendsAvailable) {
            lore.add(FRIENDS_NOTE);
        }
        lore.add("");
        lore.add("&eClick: &7change to " + colored(visibility.next()));
        return lore;
    }

    static String colored(StatisticVisibility value) {
        return switch (value) {
            case NOBODY -> "&cNobody";
            case FRIENDS -> "&bFriends";
            case EVERYONE -> "&aEveryone";
        };
    }

    /** {@code open_world} → "Open world", {@code siege} → "Siege". */
    static String contextLabel(String context) {
        if (context == null || context.isBlank()) {
            return "All";
        }
        String spaced = context.replace('_', ' ').trim();
        return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }
}

package net.knightsandkings.knk.core.domain.statistics;

import java.util.List;
import java.util.Optional;

/**
 * A player's statistics visibility settings ({@code GET api/statistics/users/{id}/visibility}).
 * {@code friendsAvailable} is false until KNG-35: "friends" is stored but shows nothing.
 */
public record StatisticsVisibilitySettings(int userId, boolean friendsAvailable, List<Setting> settings) {

    public StatisticsVisibilitySettings {
        settings = settings == null ? List.of() : List.copyOf(settings);
    }

    public Optional<Setting> setting(String settingKey) {
        return settings.stream().filter(s -> s.settingKey().equals(settingKey)).findFirst();
    }

    /**
     * One setting: its metric-level value ({@link StatisticVisibility#NOBODY} when never set) and the
     * contexts with an override or with data. A context without an override shows the inherited
     * metric-level value ({@code isOverride} false).
     */
    public record Setting(String settingKey, String group, String label, boolean contextual,
                          StatisticVisibility visibility, List<ContextValue> contexts) {
        public Setting {
            visibility = visibility == null ? StatisticVisibility.NOBODY : visibility;
            contexts = contexts == null ? List.of() : List.copyOf(contexts);
        }
    }

    public record ContextValue(String context, StatisticVisibility visibility, boolean isOverride) {
        public ContextValue {
            visibility = visibility == null ? StatisticVisibility.NOBODY : visibility;
        }
    }

    /**
     * One change of an atomic update ({@code PUT …/visibility}): {@code context} {@code ""} = the
     * metric level; {@code expected} is the value the player saw (for a context without an
     * override: the inherited metric-level value). Any mismatch rejects the whole update (409).
     */
    public record Change(String settingKey, String context, StatisticVisibility expected, StatisticVisibility visibility) {
        public Change {
            context = context == null ? "" : context;
        }
    }
}

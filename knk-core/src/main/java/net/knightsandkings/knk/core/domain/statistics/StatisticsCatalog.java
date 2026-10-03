package net.knightsandkings.knk.core.domain.statistics;

import java.util.List;
import java.util.Optional;

/**
 * The parts of {@code GET api/statistics/catalog} the plugin uses: the reporting time zone, the
 * known context keys, the visibility settings and the five menu groups (activity, combat,
 * minigames, exploration, progression) with their setting keys in display order.
 */
public record StatisticsCatalog(String timeZone, List<String> contexts, List<Setting> settings, List<Group> groups) {

    public StatisticsCatalog {
        contexts = contexts == null ? List.of() : List.copyOf(contexts);
        settings = settings == null ? List.of() : List.copyOf(settings);
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public Optional<Group> group(String key) {
        return groups.stream().filter(g -> g.key().equalsIgnoreCase(key)).findFirst();
    }

    public record Setting(String settingKey, String group, String label, boolean contextual) {
    }

    public record Group(String key, String label, List<String> settingKeys) {
        public Group {
            settingKeys = settingKeys == null ? List.of() : List.copyOf(settingKeys);
        }
    }
}

package net.knightsandkings.knk.core.domain.statistics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A player's statistics as the API shows them to one viewer ({@code GET api/statistics/users/{id}},
 * KNG-34): the always-public profile plus only the metrics, economy and discovery sections that
 * viewer may see - the API applies the player's visibility settings, the plugin renders what it
 * gets. {@code viewer} is the API's classification: {@code self}, {@code staff}, {@code signedIn} or
 * {@code anonymous}.
 */
public record PlayerStatistics(int userId, String username, String period, LocalDate periodStart,
                               LocalDate periodEndExclusive, String timeZone, String viewer, Profile profile,
                               List<Metric> metrics, Economy economy, Discoveries discoveries) {

    public PlayerStatistics {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
    }

    public Optional<Metric> metric(String key) {
        return metrics.stream().filter(m -> m.key().equals(key)).findFirst();
    }

    /** Lifetime activity and progression, always public. {@code firstJoinedAt} may be null. */
    public record Profile(String titleName, int experience, int coins, int gems, Instant firstJoinedAt,
                          long activePlaytimeSeconds, long afkSeconds) {
    }

    /**
     * One visible metric. {@code value} is display-rounded by the API and null when the total is
     * hidden but some contexts are visible (a hidden context must not leak through the total);
     * {@code contexts} is empty for non-contextual metrics.
     */
    public record Metric(String key, String settingKey, Double value, String unit, String aggregation,
                         List<ContextValue> contexts) {
        public Metric {
            contexts = contexts == null ? List.of() : List.copyOf(contexts);
        }
    }

    public record ContextValue(String context, double value) {
    }

    public record Economy(long coinsEarned, long coinsSpent, long gemsEarned, long gemsSpent) {
    }

    public record Discoveries(int total, int towns, int districts, int structures) {
    }
}
